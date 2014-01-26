/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package org.apache.bookkeeper.bookie;

import com.google.common.annotations.VisibleForTesting;
import org.apache.bookkeeper.bookie.Journal.JournalScanner;
import org.apache.bookkeeper.bookie.LedgerDirsManager.LedgerDirsListener;
import org.apache.bookkeeper.bookie.LedgerDirsManager.NoWritableLedgerDirException;
import org.apache.bookkeeper.conf.ServerConfiguration;
import org.apache.bookkeeper.jmx.BKMBeanInfo;
import org.apache.bookkeeper.jmx.BKMBeanRegistry;
import org.apache.bookkeeper.meta.LedgerManager;
import org.apache.bookkeeper.meta.LedgerManagerFactory;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.WriteCallback;
import org.apache.bookkeeper.util.*;
import org.apache.bookkeeper.util.net.DNS;
import org.apache.bookkeeper.zookeeper.ZooKeeperWatcherBase;
import org.apache.commons.io.FileUtils;
import org.apache.zookeeper.*;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Charsets.UTF_8;

/**
 * Implements a bookie.
 *
 */

public class Bookie extends BookieCriticalThread {

    private final static Logger LOG = LoggerFactory.getLogger(Bookie.class);

    final File journalDirectory;
    final ServerConfiguration conf;

    final SyncThread syncThread;
    final LedgerManagerFactory ledgerManagerFactory;
    final LedgerManager ledgerManager;
    final LedgerStorage ledgerStorage;
    final Journal journal;

    final HandleFactory handles;

    static final long METAENTRY_ID_LEDGER_KEY = -0x1000;
    static final long METAENTRY_ID_FENCE_KEY  = -0x2000;

    // ZK registration path for this bookie
    private final String bookieRegistrationPath;

    private LedgerDirsManager ledgerDirsManager;
    private LedgerDirsManager indexDirsManager;

    // ZooKeeper client instance for the Bookie
    ZooKeeper zk;

    // Running flag
    private volatile boolean running = false;
    // Flag identify whether it is in shutting down progress
    private volatile boolean shuttingdown = false;

    private int exitCode = ExitCode.OK;

    // jmx related beans
    BookieBean jmxBookieBean;
    BKMBeanInfo jmxLedgerStorageBean;

    final ConcurrentMap<Long, byte[]> masterKeyCache = new ConcurrentHashMap<Long, byte[]>();

    final private String zkBookieRegPath;

    final private AtomicBoolean readOnly = new AtomicBoolean(false);

    public static class NoLedgerException extends IOException {
        private static final long serialVersionUID = 1L;
        private final long ledgerId;
        public NoLedgerException(long ledgerId) {
            super("Ledger " + ledgerId + " not found");
            this.ledgerId = ledgerId;
        }
        public long getLedgerId() {
            return ledgerId;
        }
    }
    public static class NoEntryException extends IOException {
        private static final long serialVersionUID = 1L;
        private final long ledgerId;
        private final long entryId;
        public NoEntryException(long ledgerId, long entryId) {
            this("Entry " + entryId + " not found in " + ledgerId, ledgerId, entryId);
        }

        public NoEntryException(String msg, long ledgerId, long entryId) {
            super(msg);
            this.ledgerId = ledgerId;
            this.entryId = entryId;
        }

        public long getLedger() {
            return ledgerId;
        }
        public long getEntry() {
            return entryId;
        }
    }

    // Write Callback do nothing
    static class NopWriteCallback implements WriteCallback {
        @Override
        public void writeComplete(int rc, long ledgerId, long entryId,
                                  InetSocketAddress addr, Object ctx) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Finished writing entry {} @ ledger {} for {} : {}",
                          new Object[] { entryId, ledgerId, addr, rc });
            }
        }
    }

    final static Future<Boolean> SUCCESS_FUTURE = new Future<Boolean>() {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) { return false; }
        @Override
        public Boolean get() { return true; }
        @Override
        public Boolean get(long timeout, TimeUnit unit) { return true; }
        @Override
        public boolean isCancelled() { return false; }
        @Override
        public boolean isDone() {
            return true;
        }
    };

    static class CountDownLatchFuture<T> implements Future<T> {

        T value = null;
        volatile boolean done = false;
        CountDownLatch latch = new CountDownLatch(1);

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) { return false; }
        @Override
        public T get() throws InterruptedException {
            latch.await();
            return value;
        }
        @Override
        public T get(long timeout, TimeUnit unit)
            throws InterruptedException, TimeoutException {
            if (!latch.await(timeout, unit)) {
                throw new TimeoutException("Timed out waiting for latch");
            }
            return value;
        }

        @Override
        public boolean isCancelled() { return false; }

        @Override
        public boolean isDone() {
            return done;
        }

        void setDone(T value) {
            this.value = value;
            done = true;
            latch.countDown();
        }
    }

    static class FutureWriteCallback implements WriteCallback {

        CountDownLatchFuture<Boolean> result =
            new CountDownLatchFuture<Boolean>();

        @Override
        public void writeComplete(int rc, long ledgerId, long entryId,
                                  InetSocketAddress addr, Object ctx) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Finished writing entry {} @ ledger {} for {} : {}",
                          new Object[] { entryId, ledgerId, addr, rc });
            }
            result.setDone(0 == rc);
        }

        public Future<Boolean> getResult() {
            return result;
        }
    }

    public static void checkDirectoryStructure(File dir) throws IOException {
        if (!dir.exists()) {
            File parent = dir.getParentFile();
            File preV3versionFile = new File(dir.getParent(),
                    BookKeeperConstants.VERSION_FILENAME);

            final AtomicBoolean oldDataExists = new AtomicBoolean(false);
            parent.list(new FilenameFilter() {
                    public boolean accept(File dir, String name) {
                        if (name.endsWith(".txn") || name.endsWith(".idx") || name.endsWith(".log")) {
                            oldDataExists.set(true);
                        }
                        return true;
                    }
                });
            if (preV3versionFile.exists() || oldDataExists.get()) {
                String err = "Directory layout version is less than 3, upgrade needed";
                LOG.error(err);
                throw new IOException(err);
            }
            if (!dir.mkdirs()) {
                String err = "Unable to create directory " + dir;
                LOG.error(err);
                throw new IOException(err);
            }
        }
    }

    /**
     * Check that the environment for the bookie is correct.
     * This means that the configuration has stayed the same as the
     * first run and the filesystem structure is up to date.
     */
    private void checkEnvironment(ZooKeeper zk) throws BookieException, IOException {
        List<File> allLedgerDirs = new ArrayList<File>(ledgerDirsManager.getAllLedgerDirs().size()
                                                     + indexDirsManager.getAllLedgerDirs().size());
        try {
            zk.create("/ledgers", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.create("/ledgers/available", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.create("/ledgers/available/readonly", new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (KeeperException e) {
            e.printStackTrace();  // TODO: Customise this generated block
        } catch (InterruptedException e) {
            e.printStackTrace();  // TODO: Customise this generated block
        }

        allLedgerDirs.addAll(ledgerDirsManager.getAllLedgerDirs());
        if (indexDirsManager != ledgerDirsManager) {
            allLedgerDirs.addAll(indexDirsManager.getAllLedgerDirs());
        }
        if (zk == null) { // exists only for testing, just make sure directories are correct
            checkDirectoryStructure(journalDirectory);
            for (File dir : allLedgerDirs) {
                    checkDirectoryStructure(dir);
            }
            return;
        }
        try {
            String instanceId = getInstanceId(zk);
            boolean newEnv = false;
            Cookie masterCookie = Cookie.generateCookie(conf);
            if (null != instanceId) {
                masterCookie.setInstanceId(instanceId);
            }
            try {
                Cookie zkCookie = Cookie.readFromZooKeeper(zk, conf);
                masterCookie.verify(zkCookie);
            } catch (KeeperException.NoNodeException nne) {
                newEnv = true;
            }
            List<File> missedCookieDirs = new ArrayList<File>();
            checkDirectoryStructure(journalDirectory);

            // try to read cookie from journal directory
            try {
                Cookie journalCookie = Cookie.readFromDirectory(journalDirectory);
                journalCookie.verify(masterCookie);
            } catch (FileNotFoundException fnf) {
                missedCookieDirs.add(journalDirectory);
            }
            for (File dir : allLedgerDirs) {
                checkDirectoryStructure(dir);
                try {
                    Cookie c = Cookie.readFromDirectory(dir);
                    c.verify(masterCookie);
                } catch (FileNotFoundException fnf) {
                    missedCookieDirs.add(dir);
                }
            }

            if (!newEnv && missedCookieDirs.size() > 0){
                LOG.error("Cookie exists in zookeeper, but not in all local directories. "
                        + " Directories missing cookie file are " + missedCookieDirs);
                throw new BookieException.InvalidCookieException();
            }
            if (newEnv) {
                if (missedCookieDirs.size() > 0) {
                    LOG.debug("Directories missing cookie file are {}", missedCookieDirs);
                    masterCookie.writeToDirectory(journalDirectory);
                    for (File dir : allLedgerDirs) {
                        masterCookie.writeToDirectory(dir);
                    }
                }
                masterCookie.writeToZooKeeper(zk, conf);
            }
        } catch (KeeperException ke) {
            LOG.error("Couldn't access cookie in zookeeper", ke);
            throw new BookieException.InvalidCookieException(ke);
        } catch (UnknownHostException uhe) {
            LOG.error("Couldn't check cookies, networking is broken", uhe);
            throw new BookieException.InvalidCookieException(uhe);
        } catch (IOException ioe) {
            LOG.error("Error accessing cookie on disks", ioe);
            throw new BookieException.InvalidCookieException(ioe);
        } catch (InterruptedException ie) {
            LOG.error("Thread interrupted while checking cookies, exiting", ie);
            throw new BookieException.InvalidCookieException(ie);
        }
    }

    /**
     * Return the configured address of the bookie.
     */
    public static InetSocketAddress getBookieAddress(ServerConfiguration conf)
            throws UnknownHostException {
        String iface = conf.getListeningInterface();
        if (iface == null) {
            iface = "default";
        }
        InetSocketAddress addr = new InetSocketAddress(
                DNS.getDefaultHost(iface),
                conf.getBookiePort());
        if (addr.getAddress().isLoopbackAddress()
            && !conf.getAllowLoopback()) {
            throw new UnknownHostException("Trying to listen on loopback address, "
                    + addr + " but this is forbidden by default "
                    + "(see ServerConfiguration#getAllowLoopback())");
        }
        return addr;
    }

    private String getInstanceId(ZooKeeper zk) throws KeeperException,
            InterruptedException {
        String instanceId = null;
        if (zk.exists(conf.getZkLedgersRootPath(), null) == null) {
            LOG.error("BookKeeper metadata doesn't exist in zookeeper. "
                      + "Has the cluster been initialized? "
                      + "Try running bin/bookkeeper shell metaformat");
            throw new KeeperException.NoNodeException("BookKeeper metadata");
        }
        try {
            byte[] data = zk.getData(conf.getZkLedgersRootPath() + "/"
                    + BookKeeperConstants.INSTANCEID, false, null);
            instanceId = new String(data, UTF_8);
        } catch (KeeperException.NoNodeException e) {
            LOG.info("INSTANCEID not exists in zookeeper. Not considering it for data verification");
        }
        return instanceId;
    }

    public LedgerDirsManager getLedgerDirsManager() {
        return ledgerDirsManager;
    }

    LedgerDirsManager getIndexDirsManager() {
        return indexDirsManager;
    }

    public static File getCurrentDirectory(File dir) {
        return new File(dir, BookKeeperConstants.CURRENT_DIR);
    }

    public static File[] getCurrentDirectories(File[] dirs) {
        File[] currentDirs = new File[dirs.length];
        for (int i = 0; i < dirs.length; i++) {
            currentDirs[i] = getCurrentDirectory(dirs[i]);
        }
        return currentDirs;
    }


    public Bookie(ServerConfiguration conf)
            throws IOException, KeeperException, InterruptedException, BookieException {
        super("Bookie-" + conf.getBookiePort());
        this.bookieRegistrationPath = conf.getZkAvailableBookiesPath() + "/";
        this.conf = conf;
        this.journalDirectory = getCurrentDirectory(conf.getJournalDir());
        this.ledgerDirsManager = new LedgerDirsManager(conf, conf.getLedgerDirs());
        File[] idxDirs = conf.getIndexDirs();
        if (null == idxDirs) {
            this.indexDirsManager = this.ledgerDirsManager;
        } else {
            this.indexDirsManager = new LedgerDirsManager(conf, idxDirs);
        }

        // instantiate zookeeper client to initialize ledger manager
        this.zk = instantiateZookeeperClient(conf);
        checkEnvironment(this.zk);
        ledgerManagerFactory = LedgerManagerFactory.newLedgerManagerFactory(conf, this.zk);
        LOG.info("instantiate ledger manager {}", ledgerManagerFactory.getClass().getName());
        ledgerManager = ledgerManagerFactory.newLedgerManager();

        // Initialise ledgerDirManager. This would look through all the
        // configured directories. When disk errors or all the ledger
        // directories are full, would throws exception and fail bookie startup.
        this.ledgerDirsManager.init();
        // instantiate the journal
        journal = new Journal(conf, ledgerDirsManager);
        ledgerStorage = new InterleavedLedgerStorage(conf, ledgerManager,
                                                     ledgerDirsManager, indexDirsManager,
                                                     journal);
        syncThread = new SyncThread(conf, getLedgerDirsListener(),
                                    ledgerStorage, journal);

        handles = new HandleFactoryImpl(ledgerStorage);

        // ZK ephemeral node for this Bookie.
        zkBookieRegPath = this.bookieRegistrationPath + getMyId();
    }

    private String getMyId() throws UnknownHostException {
        return StringUtils.addrToString(Bookie.getBookieAddress(conf));
    }

    void readJournal() throws IOException, BookieException {
        journal.replay(new JournalScanner() {
            @Override
            public void process(int journalVersion, long offset, ByteBuffer recBuff) throws IOException {
                long ledgerId = recBuff.getLong();
                long entryId = recBuff.getLong();
                try {
                    LOG.debug("Replay journal - ledger id : {}, entry id : {}.", ledgerId, entryId);
                    if (entryId == METAENTRY_ID_LEDGER_KEY) {
                        if (journalVersion >= 3) {
                            int masterKeyLen = recBuff.getInt();
                            byte[] masterKey = new byte[masterKeyLen];

                            recBuff.get(masterKey);
                            masterKeyCache.put(ledgerId, masterKey);
                        } else {
                            throw new IOException("Invalid journal. Contains journalKey "
                                    + " but layout version (" + journalVersion
                                    + ") is too old to hold this");
                        }
                    } else if (entryId == METAENTRY_ID_FENCE_KEY) {
                        if (journalVersion >= 4) {
                            byte[] key = masterKeyCache.get(ledgerId);
                            if (key == null) {
                                key = ledgerStorage.readMasterKey(ledgerId);
                            }
                            LedgerDescriptor handle = handles.getHandle(ledgerId, key);
                            handle.setFenced();
                        } else {
                            throw new IOException("Invalid journal. Contains fenceKey "
                                    + " but layout version (" + journalVersion
                                    + ") is too old to hold this");
                        }
                    } else {
                        byte[] key = masterKeyCache.get(ledgerId);
                        if (key == null) {
                            key = ledgerStorage.readMasterKey(ledgerId);
                        }
                        LedgerDescriptor handle = handles.getHandle(ledgerId, key);

                        recBuff.rewind();
                        handle.addEntry(recBuff);
                    }
                } catch (NoLedgerException nsle) {
                    LOG.debug("Skip replaying entries of ledger {} since it was deleted.", ledgerId);
                } catch (BookieException be) {
                    throw new IOException(be);
                }
            }
        });
    }

    synchronized public void start() {
        setDaemon(true);
        LOG.debug("I'm starting a bookie with journal directory {}", journalDirectory.getName());
        //Start DiskChecker thread
        ledgerDirsManager.start();
        if (indexDirsManager != ledgerDirsManager) {
            indexDirsManager.start();
        }
        // replay journals
        try {
            readJournal();
        } catch (IOException ioe) {
            LOG.error("Exception while replaying journals, shutting down", ioe);
            shutdown(ExitCode.BOOKIE_EXCEPTION);
            return;
        } catch (BookieException be) {
            LOG.error("Exception while replaying journals, shutting down", be);
            shutdown(ExitCode.BOOKIE_EXCEPTION);
            return;
        }
        LOG.info("Finished reading journal, starting bookie");
        // start bookie thread
        super.start();

        // After successful bookie startup, register listener for disk
        // error/full notifications.
        ledgerDirsManager.addLedgerDirsListener(getLedgerDirsListener());
        if (indexDirsManager != ledgerDirsManager) {
            indexDirsManager.addLedgerDirsListener(getLedgerDirsListener());
        }

        ledgerStorage.start();

        syncThread.start();
        // set running here.
        // since bookie server use running as a flag to tell bookie server whether it is alive
        // if setting it in bookie thread, the watcher might run before bookie thread.
        running = true;
        try {
            registerBookie(conf);
        } catch (IOException e) {
            LOG.error("Couldn't register bookie with zookeeper, shutting down", e);
            shutdown(ExitCode.ZK_REG_FAIL);
        }
    }

    /*
     * Get the DiskFailure listener for the bookie
     */
    private LedgerDirsListener getLedgerDirsListener() {

        return new LedgerDirsListener() {

            @Override
            public void diskFull(File disk) {
                // Nothing needs to be handled here.
            }

            @Override
            public void diskFailed(File disk) {
                // Shutdown the bookie on disk failure.
                triggerBookieShutdown(ExitCode.BOOKIE_EXCEPTION);
            }

            @Override
            public void allDisksFull() {
                // Transition to readOnly mode on all disks full
                transitionToReadOnlyMode();
            }

            @Override
            public void fatalError() {
                LOG.error("Fatal error reported by ledgerDirsManager");
                triggerBookieShutdown(ExitCode.BOOKIE_EXCEPTION);
            }
        };
    }

    /**
     * Register jmx with parent
     *
     * @param parent parent bk mbean info
     */
    public void registerJMX(BKMBeanInfo parent) {
        try {
            jmxBookieBean = new BookieBean(this);
            BKMBeanRegistry.getInstance().register(jmxBookieBean, parent);

            try {
                jmxLedgerStorageBean = this.ledgerStorage.getJMXBean();
                BKMBeanRegistry.getInstance().register(jmxLedgerStorageBean, jmxBookieBean);
            } catch (Exception e) {
                LOG.warn("Failed to register with JMX for ledger cache", e);
                jmxLedgerStorageBean = null;
            }
        } catch (Exception e) {
            LOG.warn("Failed to register with JMX", e);
            jmxBookieBean = null;
        }
    }

    /**
     * Unregister jmx
     */
    public void unregisterJMX() {
        try {
            if (jmxLedgerStorageBean != null) {
                BKMBeanRegistry.getInstance().unregister(jmxLedgerStorageBean);
            }
        } catch (Exception e) {
            LOG.warn("Failed to unregister with JMX", e);
        }
        try {
            if (jmxBookieBean != null) {
                BKMBeanRegistry.getInstance().unregister(jmxBookieBean);
            }
        } catch (Exception e) {
            LOG.warn("Failed to unregister with JMX", e);
        }
        jmxBookieBean = null;
        jmxLedgerStorageBean = null;
    }


    /**
     * Instantiate the ZooKeeper client for the Bookie.
     */
    private ZooKeeper instantiateZookeeperClient(ServerConfiguration conf)
            throws IOException, InterruptedException, KeeperException {
        if (conf.getZkServers() == null) {
            LOG.warn("No ZK servers passed to Bookie constructor so BookKeeper clients won't know about this server!");
            return null;
        }
        // Create the ZooKeeper client instance
        return newZookeeper(conf.getZkServers(), conf.getZkTimeout());
    }

    /**
     * Register as an available bookie
     */
    protected void registerBookie(ServerConfiguration conf) throws IOException {
        if (null == zk) {
            // zookeeper instance is null, means not register itself to zk
            return;
        }

        // ZK ephemeral node for this Bookie.
        String zkBookieRegPath = this.bookieRegistrationPath
            + StringUtils.addrToString(getBookieAddress(conf));
        final CountDownLatch prevNodeLatch = new CountDownLatch(1);
        try{
            Watcher zkPrevRegNodewatcher = new Watcher() {
                @Override
                public void process(WatchedEvent event) {
                    // Check for prev znode deletion. Connection expiration is
                    // not handling, since bookie has logic to shutdown.
                    if (EventType.NodeDeleted == event.getType()) {
                        prevNodeLatch.countDown();
                    }
                }
            };
            if (null != zk.exists(zkBookieRegPath, zkPrevRegNodewatcher)) {
                LOG.info("Previous bookie registration znode: "
                        + zkBookieRegPath
                        + " exists, so waiting zk sessiontimeout: "
                        + conf.getZkTimeout() + "ms for znode deletion");
                // waiting for the previous bookie reg znode deletion
                if (!prevNodeLatch.await(conf.getZkTimeout(),
                        TimeUnit.MILLISECONDS)) {
                    throw new KeeperException.NodeExistsException(
                            zkBookieRegPath);
                }
            }

            // Create the ZK ephemeral node for this Bookie.
            zk.create(zkBookieRegPath, new byte[0], Ids.OPEN_ACL_UNSAFE,
                    CreateMode.EPHEMERAL);
        } catch (KeeperException ke) {
            LOG.error("ZK exception registering ephemeral Znode for Bookie!",
                    ke);
            // Throw an IOException back up. This will cause the Bookie
            // constructor to error out. Alternatively, we could do a System
            // exit here as this is a fatal error.
            throw new IOException(ke);
        } catch (InterruptedException ie) {
            LOG.error("ZK exception registering ephemeral Znode for Bookie!",
                    ie);
            // Throw an IOException back up. This will cause the Bookie
            // constructor to error out. Alternatively, we could do a System
            // exit here as this is a fatal error.
            throw new IOException(ie);
        }
    }

    /*
     * Transition the bookie to readOnly mode
     */
    @VisibleForTesting
    public void transitionToReadOnlyMode() {
        if (shuttingdown == true) {
            return;
        }

        if (!readOnly.compareAndSet(false, true)) {
            return;
        }
        if (!conf.isReadOnlyModeEnabled()) {
            LOG.warn("ReadOnly mode is not enabled. "
                    + "Can be enabled by configuring "
                    + "'readOnlyModeEnabled=true' in configuration."
                    + "Shutting down bookie");
            triggerBookieShutdown(ExitCode.BOOKIE_EXCEPTION);
            return;
        }
        LOG.info("Transitioning Bookie to ReadOnly mode,"
                + " and will serve only read requests from clients!");
        try {
            if (null == zk.exists(this.bookieRegistrationPath
                    + BookKeeperConstants.READONLY, false)) {
                try {
                    zk.create(this.bookieRegistrationPath
                            + BookKeeperConstants.READONLY, new byte[0],
                            Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                } catch (NodeExistsException e) {
                    // this node is just now created by someone.
                }
            }
            // Create the readonly node
            zk.create(this.bookieRegistrationPath
                    + BookKeeperConstants.READONLY + "/" + getMyId(),
                    new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
            // Clear the current registered node
            zk.delete(zkBookieRegPath, -1);
        } catch (IOException e) {
            LOG.error("Error in transition to ReadOnly Mode."
                    + " Shutting down", e);
            triggerBookieShutdown(ExitCode.BOOKIE_EXCEPTION);
            return;
        } catch (KeeperException e) {
            LOG.error("Error in transition to ReadOnly Mode."
                    + " Shutting down", e);
            triggerBookieShutdown(ExitCode.BOOKIE_EXCEPTION);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted Exception while transitioning to ReadOnly Mode.");
            return;
        }
    }

    /*
     * Check whether Bookie is writable
     */
    public boolean isReadOnly() {
        return readOnly.get();
    }

    /**
     * Create a new zookeeper client to zk cluster.
     *
     * <p>
     * Bookie Server just used zk client when syncing ledgers for garbage collection.
     * So when zk client is expired, it means this bookie server is not available in
     * bookie server list. The bookie client will be notified for its expiration. No
     * more bookie request will be sent to this server. So it's better to exit when zk
     * expired.
     * </p>
     * <p>
     * Since there are lots of bk operations cached in queue, so we wait for all the operations
     * are processed and quit. It is done by calling <b>shutdown</b>.
     * </p>
     *
     * @param zkServers the quorum list of zk servers
     * @param sessionTimeout session timeout of zk connection
     *
     * @return zk client instance
     */
    private ZooKeeper newZookeeper(final String zkServers,
            final int sessionTimeout) throws IOException, InterruptedException,
            KeeperException {
        ZooKeeperWatcherBase w = new ZooKeeperWatcherBase(conf.getZkTimeout()) {
            @Override
            public void process(WatchedEvent event) {
                // Check for expired connection.
                if (event.getState().equals(Watcher.Event.KeeperState.Expired)) {
                    LOG.error("ZK client connection to the ZK server has expired!");
                    shutdown(ExitCode.ZK_EXPIRED);
                } else {
                    super.process(event);
                }
            }
        };
        return ZkUtils.createConnectedZookeeperClient(zkServers, w);
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void run() {
        // bookie thread wait for journal thread
        try {
            // start journal
            journal.start();
            // wait until journal quits
            journal.join();
        } catch (InterruptedException ie) {
        }
        // if the journal thread quits due to shutting down, it is ok
        if (!shuttingdown) {
            // some error found in journal thread and it quits
            // following add operations to it would hang unit client timeout
            // so we should let bookie server exists
            LOG.error("Journal manager quits unexpectedly.");
            triggerBookieShutdown(ExitCode.BOOKIE_EXCEPTION);
        }
    }

    // Triggering the Bookie shutdown in its own thread,
    // because shutdown can be called from sync thread which would be
    // interrupted by shutdown call.
    AtomicBoolean shutdownTriggered = new AtomicBoolean(false);
    void triggerBookieShutdown(final int exitCode) {
        if (!shutdownTriggered.compareAndSet(false, true)) {
            return;
        }
        LOG.info("Triggering shutdown of Bookie-{} with exitCode {}",
                 conf.getBookiePort(), exitCode);
        BookieThread th = new BookieThread("BookieShutdownTrigger") {
            public void run() {
                Bookie.this.shutdown(exitCode);
            }
        };
        th.start();
    }

    // provided a public shutdown method for other caller
    // to shut down bookie gracefully
    public int shutdown() {
        return shutdown(ExitCode.OK);
    }

    // internal shutdown method to let shutdown bookie gracefully
    // when encountering exception
    synchronized int shutdown(int exitCode) {
        try {
            if (running) { // avoid shutdown twice
                // the exitCode only set when first shutdown usually due to exception found
                LOG.info("Shutting down Bookie-{} with exitCode {}",
                         conf.getBookiePort(), exitCode);
                this.exitCode = exitCode;
                // mark bookie as in shutting down progress
                shuttingdown = true;

                // Shutdown journal
                journal.shutdown();
                this.join();
                syncThread.shutdown();

                // Shutdown the EntryLogger which has the GarbageCollector Thread running
                ledgerStorage.shutdown();

                // close Ledger Manager
                try {
                    ledgerManager.close();
                    ledgerManagerFactory.uninitialize();
                } catch (IOException ie) {
                    LOG.error("Failed to close active ledger manager : ", ie);
                }

                //Shutdown disk checker
                ledgerDirsManager.shutdown();
                if (indexDirsManager != ledgerDirsManager) {
                    indexDirsManager.shutdown();
                }

                // Shutdown the ZK client
                if(zk != null) zk.close();

                // setting running to false here, so watch thread
                // in bookie server know it only after bookie shut down
                running = false;
            }
        } catch (InterruptedException ie) {
            LOG.error("Interrupted during shutting down bookie : ", ie);
        }
        return this.exitCode;
    }

    /** 
     * Retrieve the ledger descriptor for the ledger which entry should be added to.
     * The LedgerDescriptor returned from this method should be eventually freed with 
     * #putHandle().
     *
     * @throws BookieException if masterKey does not match the master key of the ledger
     */
    private LedgerDescriptor getLedgerForEntry(ByteBuffer entry, byte[] masterKey) 
            throws IOException, BookieException {
        long ledgerId = entry.getLong();
        LedgerDescriptor l = handles.getHandle(ledgerId, masterKey);
        if (!masterKeyCache.containsKey(ledgerId)) {
            // new handle, we should add the key to journal ensure we can rebuild
            ByteBuffer bb = ByteBuffer.allocate(8 + 8 + 4 + masterKey.length);
            bb.putLong(ledgerId);
            bb.putLong(METAENTRY_ID_LEDGER_KEY);
            bb.putInt(masterKey.length);
            bb.put(masterKey);
            bb.flip();

            if (null == masterKeyCache.putIfAbsent(ledgerId, masterKey)) {
                journal.logAddEntry(bb, new NopWriteCallback(), null);
            }
        }
        return l;
    }

    protected void addEntryByLedgerId(long ledgerId, ByteBuffer entry)
        throws IOException, BookieException {
        byte[] key = ledgerStorage.readMasterKey(ledgerId);
        LedgerDescriptor handle = handles.getHandle(ledgerId, key);
        handle.addEntry(entry);
    }

    /**
     * Add an entry to a ledger as specified by handle. 
     */
    private void addEntryInternal(LedgerDescriptor handle, ByteBuffer entry, WriteCallback cb, Object ctx)
            throws IOException, BookieException {
        long ledgerId = handle.getLedgerId();
        entry.rewind();
        long entryId = handle.addEntry(entry);

        entry.rewind();
        LOG.trace("Adding {}@{}", entryId, ledgerId);
        journal.logAddEntry(entry, cb, ctx);
    }

    /**
     * Add entry to a ledger, even if the ledger has previous been fenced. This should only
     * happen in bookie recovery or ledger recovery cases, where entries are being replicates 
     * so that they exist on a quorum of bookies. The corresponding client side call for this
     * is not exposed to users.
     */
    public void recoveryAddEntry(ByteBuffer entry, WriteCallback cb, Object ctx, byte[] masterKey) 
            throws IOException, BookieException {
        try {
            LedgerDescriptor handle = getLedgerForEntry(entry, masterKey);
            synchronized (handle) {
                addEntryInternal(handle, entry, cb, ctx);
            }
        } catch (NoWritableLedgerDirException e) {
            transitionToReadOnlyMode();
            throw new IOException(e);
        }
    }
    
    /** 
     * Add entry to a ledger.
     * @throws BookieException.LedgerFencedException if the ledger is fenced
     */
    public void addEntry(ByteBuffer entry, WriteCallback cb, Object ctx, byte[] masterKey)
            throws IOException, BookieException {
        try {
            LedgerDescriptor handle = getLedgerForEntry(entry, masterKey);
            synchronized (handle) {
                if (handle.isFenced()) {
                    throw BookieException
                            .create(BookieException.Code.LedgerFencedException);
                }
                addEntryInternal(handle, entry, cb, ctx);
            }
        } catch (NoWritableLedgerDirException e) {
            transitionToReadOnlyMode();
            throw new IOException(e);
        }
    }

    /**
     * Fences a ledger. From this point on, clients will be unable to
     * write to this ledger. Only recoveryAddEntry will be
     * able to add entries to the ledger.
     * This method is idempotent. Once a ledger is fenced, it can
     * never be unfenced. Fencing a fenced ledger has no effect.
     */
    public Future<Boolean> fenceLedger(long ledgerId, byte[] masterKey) throws IOException, BookieException {
        LedgerDescriptor handle = handles.getHandle(ledgerId, masterKey);
        boolean success;
        synchronized (handle) {
            success = handle.setFenced();
        }
        if (success) {
            // fenced first time, we should add the key to journal ensure we can rebuild
            ByteBuffer bb = ByteBuffer.allocate(8 + 8);
            bb.putLong(ledgerId);
            bb.putLong(METAENTRY_ID_FENCE_KEY);
            bb.flip();

            FutureWriteCallback fwc = new FutureWriteCallback();
            LOG.debug("record fenced state for ledger {} in journal.", ledgerId);
            journal.logAddEntry(bb, fwc, null);
            return fwc.getResult();
        } else {
            // already fenced
            return SUCCESS_FUTURE;
        }
    }

    public ByteBuffer readEntry(long ledgerId, long entryId)
            throws IOException, NoLedgerException {
        LedgerDescriptor handle = handles.getReadOnlyHandle(ledgerId);
        LOG.trace("Reading {}@{}", entryId, ledgerId);
        return handle.readEntry(entryId);
    }

    // The rest of the code is test stuff
    static class CounterCallback implements WriteCallback {
        int count;

        synchronized public void writeComplete(int rc, long l, long e, InetSocketAddress addr, Object ctx) {
            count--;
            if (count == 0) {
                notifyAll();
            }
        }

        synchronized public void incCount() {
            count++;
        }

        synchronized public void waitZero() throws InterruptedException {
            while (count > 0) {
                wait();
            }
        }
    }

    /**
     * Format the bookie server data
     * 
     * @param conf
     *            ServerConfiguration
     * @param isInteractive
     *            Whether format should ask prompt for confirmation if old data
     *            exists or not.
     * @param force
     *            If non interactive and force is true, then old data will be
     *            removed without confirm prompt.
     * @return Returns true if the format is success else returns false
     */
    public static boolean format(ServerConfiguration conf,
            boolean isInteractive, boolean force) {
        File journalDir = conf.getJournalDir();
        if (journalDir.exists() && journalDir.isDirectory()
                && journalDir.list().length != 0) {
            try {
                boolean confirm = false;
                if (!isInteractive) {
                    // If non interactive and force is set, then delete old
                    // data.
                    if (force) {
                        confirm = true;
                    } else {
                        confirm = false;
                    }
                } else {
                    confirm = IOUtils
                            .confirmPrompt("Are you sure to format Bookie data..?");
                }

                if (!confirm) {
                    LOG.error("Bookie format aborted!!");
                    return false;
                }
            } catch (IOException e) {
                LOG.error("Error during bookie format", e);
                return false;
            }
        }
        if (!cleanDir(journalDir)) {
            LOG.error("Formatting journal directory failed");
            return false;
        }

        File[] ledgerDirs = conf.getLedgerDirs();
        for (File dir : ledgerDirs) {
            if (!cleanDir(dir)) {
                LOG.error("Formatting ledger directory " + dir + " failed");
                return false;
            }
        }

        // Clean up index directories if they are separate from the ledger dirs
        File[] indexDirs = conf.getIndexDirs();
        if (null != indexDirs) {
            for (File dir : indexDirs) {
                if (!cleanDir(dir)) {
                    LOG.error("Formatting ledger directory " + dir + " failed");
                    return false;
                }
            }
        }

        LOG.info("Bookie format completed successfully");
        return true;
    }

    private static boolean cleanDir(File dir) {
        if (dir.exists()) {
            for (File child : dir.listFiles()) {
                boolean delete = FileUtils.deleteQuietly(child);
                if (!delete) {
                    LOG.error("Not able to delete " + child);
                    return false;
                }
            }
        } else if (!dir.mkdirs()) {
            LOG.error("Not able to create the directory " + dir);
            return false;
        }
        return true;
    }

    /**
     * @param args
     * @throws IOException
     * @throws InterruptedException
     */
    public static void main(String[] args) 
            throws IOException, InterruptedException, BookieException, KeeperException {
        Bookie b = new Bookie(new ServerConfiguration());
        b.start();
        CounterCallback cb = new CounterCallback();
        long start = MathUtils.now();
        for (int i = 0; i < 100000; i++) {
            ByteBuffer buff = ByteBuffer.allocate(1024);
            buff.putLong(1);
            buff.putLong(i);
            buff.limit(1024);
            buff.position(0);
            cb.incCount();
            b.addEntry(buff, cb, null, new byte[0]);
        }
        cb.waitZero();
        long end = MathUtils.now();
        System.out.println("Took " + (end-start) + "ms");
    }

    /**
     * Returns exit code - cause of failure
     *
     * @return {@link ExitCode}
     */
    public int getExitCode() {
        return exitCode;
    }
}
