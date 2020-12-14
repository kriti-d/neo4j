/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.internal.batchimport.staging;

import org.eclipse.collections.api.iterator.LongIterator;

import org.neo4j.internal.batchimport.Configuration;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.io.pagecache.tracing.cursor.PageCursorTracer;
import org.neo4j.kernel.impl.store.RecordStore;
import org.neo4j.kernel.impl.store.record.AbstractBaseRecord;

import static java.lang.Integer.min;

/**
 * Reads records from a {@link RecordStore} and sends batches of those records downstream.
 * A {@link PageCursor} is used during the life cycle of this {@link Step}, e.g. between
 * {@link #start(int)} and {@link #close()}.
 *
 * @param <RECORD> type of {@link AbstractBaseRecord}
 */
public class ReadRecordsStep<RECORD extends AbstractBaseRecord> extends ProcessorStep<LongIterator>
{
    private final RecordStore<RECORD> store;
    private final int batchSize;
    private final boolean prefetch;
    private final RecordDataAssembler<RECORD> assembler;

    public ReadRecordsStep( StageControl control, Configuration config, boolean inRecordWritingStage, RecordStore<RECORD> store,
            PageCacheTracer pageCacheTracer )
    {
        this( control, config, inRecordWritingStage, store, new RecordDataAssembler<>( store::newRecord, true ), false, pageCacheTracer );
    }

    public ReadRecordsStep( StageControl control, Configuration config, boolean inRecordWritingStage,
            RecordStore<RECORD> store, RecordDataAssembler<RECORD> converter, boolean prefetch, PageCacheTracer pageCacheTracer )
    {
        super( control, ">", config, parallelReading( config, inRecordWritingStage )
                                     // Limit reader (I/O) threads to 12, it's a high degree of concurrency and assigning more
                                     // will likely not make things faster, rather the other way around and it's difficult for
                                     // the processor assigner to proficiently understand that dynamic
                                     ? min( 12, config.maxNumberOfProcessors() )
                                     : 1, pageCacheTracer );
        this.store = store;
        this.assembler = converter;
        this.batchSize = config.batchSize();
        this.prefetch = prefetch;
    }

    private static boolean parallelReading( Configuration config, boolean inRecordWritingStage )
    {
        return (inRecordWritingStage && config.highIO())
                || (!inRecordWritingStage && config.parallelRecordReads());
    }

    @Override
    public void start( int orderingGuarantees )
    {
        super.start( orderingGuarantees | ORDER_SEND_DOWNSTREAM );
    }

    @Override
    protected void process( LongIterator idRange, BatchSender sender, PageCursorTracer cursorTracer )
    {
        if ( !idRange.hasNext() )
        {
            return;
        }

        long id = idRange.next();
        RECORD[] batch = control.reuse( () -> assembler.newBatchObject( batchSize ) );
        int i = 0;
        // Just use the first record in the batch here to satisfy the record cursor.
        // The truth is that we'll be using the read method which accepts an external record anyway so it doesn't matter.
        try ( PageCursor cursor = prefetch ? store.openPageCursorForReadingWithPrefetching( id, cursorTracer )
                                           : store.openPageCursorForReading( id, cursorTracer ) )
        {
            boolean hasNext = true;
            while ( hasNext )
            {
                if ( assembler.append( store, cursor, batch, id, i ) )
                {
                    i++;
                }
                if ( hasNext = idRange.hasNext() )
                {
                    id = idRange.next();
                }
            }
        }

        sender.send( assembler.cutOffAt( batch, i ) );
    }
}
