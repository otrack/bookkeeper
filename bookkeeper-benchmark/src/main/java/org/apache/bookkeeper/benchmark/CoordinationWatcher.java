package org.apache.bookkeeper.benchmark;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

import java.util.concurrent.CountDownLatch;

/**
 * // TODO: Document this
 *
 * @author otrack
 * @since 4.0
 */

public class CoordinationWatcher implements Watcher {

    CountDownLatch latch;

    public CoordinationWatcher(CountDownLatch latch){
        this.latch = latch;
    }

    @Override
    public void process(WatchedEvent event) {
        if (event.getType() == Event.EventType.NodeCreated) {
            latch.countDown();
        }
    }
}
