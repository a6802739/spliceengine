package com.splicemachine.async;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.splicemachine.async.KeyValue;
import com.splicemachine.async.Scanner;
import com.splicemachine.collections.NullStopIterator;
import com.splicemachine.metrics.*;
import com.splicemachine.metrics.Counter;
import com.splicemachine.metrics.Timer;
import com.splicemachine.stream.BaseCloseableStream;
import com.splicemachine.stream.CloseableStream;
import com.splicemachine.stream.StreamException;
import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;

import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author Scott Fines
 * Date: 7/22/14
 */
public class GatheringScanner implements AsyncScanner {
    private final Timer timer;
    private final Counter remoteBytesCounter;

    private final BlockingQueue<List<KeyValue>> resultQueue;

    private final SubScanner[] scanners;
    private final int maxQueueSize;

    public static AsyncScanner newScanner(int maxQueueSize,
                                          MetricFactory metricFactory,
                                          Function<Scan, Scanner> toScannerFunction,
                                          List<Scan> scans) throws IOException {
        List<Scanner> scanners = Lists.transform(scans, toScannerFunction);
        return new GatheringScanner(scanners,maxQueueSize,metricFactory);
    }

    public GatheringScanner(List<Scanner> scanners, int maxQueueSize, MetricFactory metricFactory){
        this.timer = metricFactory.newTimer();
        this.remoteBytesCounter = metricFactory.newCounter();

        this.resultQueue = new LinkedBlockingQueue<List<KeyValue>>();
        this.scanners = new SubScanner[scanners.size()];
        for(int i=0;i<scanners.size();i++){
            Scanner scanner = scanners.get(i);
            this.scanners[i] = new SubScanner(scanner,resultQueue,scanner.getMaxNumRows(),maxQueueSize);
        }
        this.maxQueueSize = maxQueueSize;
    }

    @Override
    public void open() throws IOException {
        //kick off all the scanners
        //noinspection ForLoopReplaceableByForEach
        for(int i=0;i<scanners.length;i++){
            scanners[i].ensureScansRunning();
        }
    }

    @Override public TimeView getRemoteReadTime() { return timer.getTime(); }
    @Override public long getRemoteBytesRead() { return remoteBytesCounter.getTotal(); }
    @Override public long getRemoteRowsRead() { return timer.getNumEvents(); }
    @Override public TimeView getLocalReadTime() { return Metrics.noOpTimeView(); }
    @Override public long getLocalBytesRead() { return 0l; }
    @Override public long getLocalRowsRead() { return 0; }


    private static final List<KeyValue> POISON_PILL = Collections.emptyList();
    public List<KeyValue> nextKeyValues() throws IOException{
        List<KeyValue> kvs = resultQueue.poll();
        if(kvs==null){
            /*
             * The queue is empty. This happens in one of two situations:
             *
             * 1. All scanners are running, but none have returned any data (e.g. the consumer is much
             * faster than the producers)
             * 2. All scanners are finished running, in which case we are done
             * 3. All scanners stopped processing for some reason.
             *
             * To deal with #1 and #3, we iterate over the scanners and make sure that they are running
             * and haven't stopped work yet. Dealing with #2 is a byproduct of the scanner iteration--if
             * we can't find any scanners that aren't done, then we know that we are exhausted
             */
            if(!submitNewScans()){
                //all scans are finished, so the scanner is exhausted
                return null;
            }
            try {
                //we've submitted a new scan, now we just wait for some data to become available
                kvs = resultQueue.take();
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }

        if(kvs==POISON_PILL){
            /*
             * When a subscanner is finished, it has nothing to add to the queue. If the gathering
             * thread is in the take() call above, then it will never receive notification from that scanner,
             * so it would continue forever. To prevent this, when a subscanner completes, it offers a
             * POISON_PILL, which forces the gathering thread out of the take() block. However, POISON_PILL
             * is not a valid element, so when we see THAT EXACT OBJECT, then we must discard it and
             * loop back around to collect new results and/or to finish the scan.
             */
            return nextKeyValues();
        }else{
            /*
             * We have data, which is good. However, we need to make sure that scans are in flight
             * if we have room for them in the queue--that is, if the queue size is small enough, then
             * force the scanners to resubmit
             */
            if(resultQueue.size()<maxQueueSize)
                submitNewScans();
            return kvs;
        }
    }

    @Override
    public CloseableStream<List<KeyValue>> stream() {
        return new BaseCloseableStream<List<KeyValue>>() {
            @Override public void close() throws IOException { GatheringScanner.this.close(); }

            @Override
            public List<KeyValue> next() throws StreamException {
                try {
                    return nextKeyValues();
                } catch (IOException e) {
                    throw new StreamException(e);
                }
            }
        };
    }

    protected boolean submitNewScans() {
        boolean submitted=false;
        //noinspection ForLoopReplaceableByForEach
        for(int i=0;i<scanners.length;i++){
            SubScanner scanner = scanners[i];
            if (scanner.ensureScansRunning()) {
                submitted = true;
            }
        }
        return submitted;
    }

    @Override
    public Result next() throws IOException {
        List<KeyValue> kvs = nextKeyValues();
        if(kvs==null||kvs.size()<=0)
            return null;
        return new Result(AsyncScannerUtils.convertFromAsync(kvs));
    }

    @Override
    public Result[] next(int nbRows) throws IOException {
        List<Result> results = Lists.newArrayListWithExpectedSize(nbRows);
        for(int i=0;i<nbRows;i++){
            List<KeyValue> kvs = nextKeyValues();
            if(kvs==null||kvs.size()<=0)
                return results.toArray(new Result[results.size()]);
            results.add(new Result(AsyncScannerUtils.convertFromAsync(kvs)));
        }
        return results.toArray(new Result[results.size()]);
    }

    @Override
    public void close() {
        //noinspection ForLoopReplaceableByForEach
        for(int i=0;i<scanners.length;i++){
            scanners[i].close();
        }
    }

    @Override
    public Iterator<Result> iterator() {
        return new NullStopIterator<Result>() {
            @Override protected Result nextItem() throws IOException { return GatheringScanner.this.next(); }
            @Override public void close() throws IOException { GatheringScanner.this.close(); }
        };
    }

    private static class SubScanner implements Callback<Void, ArrayList<ArrayList<KeyValue>>> {
        private final Queue<List<KeyValue>> resultQueue;
        private final Scanner scanner;
        private final int batchSize;
        /*
         * A limit to the size of the queue. This prevents runaway gather scans
         * from hogging all our memory and collapsing under the weight. In essence,
         * we add a bunch of items, then check the size of the queue. If the queue
         * size exceeds maxQueueSize, we don't submit another request. Then, it is up
         * to the gathering thread to kick off another request on this scanner when
         * the queue has decreased below the allowed volume.
         */
        private final int maxQueueSize;

        private volatile Deferred<Void> request;
        private volatile boolean done = false;

        private SubScanner(Scanner scanner,
                           Queue<List<KeyValue>> resultQueue,
                           int batchSize, int maxQueueSize) {
            this.resultQueue = resultQueue;
            this.scanner = scanner;
            this.batchSize = batchSize;
            this.maxQueueSize = maxQueueSize;
        }

        boolean isDone(){
            return done;
        }

        boolean ensureScansRunning(){
            /*
             * Called to ensure that the scanner is working. If there are no
             * outstanding requests in flight, then this will submit a new one
             */
            if(done) return false; //nothing to do, scanner is exhausted

            /*
             * Either request==null, which means that the scan needs to be submitted,
             * or it is not null, in which case a scan is outstanding. Either way,
             * we tell the caller that the scan is still active (e.g. return true);
             */
            if(request==null)
                request = scanner.nextRows().addCallback(this);

            return true;
        }

        @Override
        public Void call(ArrayList<ArrayList<KeyValue>> arg) throws Exception {
            if(arg==null){
                //the scanner returns no more rows, so it's done
                resultQueue.offer(POISON_PILL);
                done = true;
                return null;
            }
            //add everything to the queue
            resultQueue.addAll(arg);
            if(resultQueue.size()>=maxQueueSize){
                 /*
                  * The queue is full, so don't issue another request--the gathering thread
                  * will tell us when its safe to start again
                  */
                request=null;
                return null;
            }

            if(scanner.onFinalRegion() && arg.size()<batchSize){
                /*
                 * We know by construction, and the nature of HBase scanners, that we won't
                 * be getting any more rows from this scan, even though we aren't technically finished yet.
                 * Therefore, close the scan and call us done
                 */
                done =true;
                scanner.close();
                return null;
            }
            /*
             * We can safely issue another request, so go ahead and do it
             */
            request = scanner.nextRows().addCallback(this);

            return null;
        }

        public void close() {
            done=true;
            scanner.close();
        }
    }

}