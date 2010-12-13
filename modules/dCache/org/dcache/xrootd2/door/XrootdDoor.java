package org.dcache.xrootd2.door;

import java.io.PrintWriter;


import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.security.auth.Subject;
import org.dcache.auth.LoginReply;
import org.dcache.auth.LoginStrategy;
import org.dcache.vehicles.PnfsListDirectoryMessage;
import org.dcache.vehicles.XrootdDoorAdressInfoMessage;
import org.dcache.vehicles.XrootdProtocolInfo;
import org.dcache.xrootd2.security.AbstractAuthorizationFactory;
import org.dcache.namespace.FileAttribute;
import org.dcache.namespace.FileType;
import org.dcache.util.Transfer;
import org.dcache.util.PingMoversTask;
import org.dcache.cells.AbstractCellComponent;
import org.dcache.cells.CellMessageReceiver;
import org.dcache.cells.CellCommandListener;
import org.dcache.cells.CellStub;
import org.dcache.cells.MessageCallback;
import diskCacheV111.movers.NetIFContainer;
import diskCacheV111.util.CacheException;
import diskCacheV111.util.TimeoutCacheException;
import diskCacheV111.util.PermissionDeniedCacheException;
import diskCacheV111.util.FileMetaData;
import diskCacheV111.util.PnfsHandler;
import diskCacheV111.util.FsPath;
import diskCacheV111.vehicles.DoorTransferFinishedMessage;
import diskCacheV111.vehicles.IoDoorInfo;
import diskCacheV111.vehicles.IoDoorEntry;
import dmg.cells.nucleus.CellVersion;
import dmg.cells.nucleus.NoRouteToCellException;
import dmg.cells.services.login.LoginManagerChildrenInfo;
import dmg.util.Args;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Required;

/**
 * Shared cell component used to interface with the rest of
 * dCache.
 *
 * Current implementation is more or less a copy of the old xrootd
 * code. Should be replaced by the equivalent component developed by
 * Tatjana and Tigran.
 */
public class XrootdDoor
    extends AbstractCellComponent
    implements CellMessageReceiver,
               CellCommandListener
{
    public final static String XROOTD_PROTOCOL_STRING = "Xrootd";
    public final static int XROOTD_PROTOCOL_MAJOR_VERSION = 2;
    public final static int XROOTD_PROTOCOL_MINOR_VERSION = 7;
    public final static String XROOTD_PROTOCOL_VERSION =
        String.format("%d.%d",
                      XROOTD_PROTOCOL_MAJOR_VERSION,
                      XROOTD_PROTOCOL_MINOR_VERSION);

    private final static Logger _log =
        LoggerFactory.getLogger(XrootdDoor.class);

    private final static AtomicInteger _handleCounter = new AtomicInteger();

    private final static long PING_DELAY = 300000;

    private String _cellName;
    private String _domainName;

    private AbstractAuthorizationFactory _authzFactory;

    /** authorization decisions */
    private LoginStrategy _strategy;

    private List<FsPath> _readPaths = Collections.singletonList(new FsPath());
    private List<FsPath> _writePaths = Collections.singletonList(new FsPath());

    private CellStub _poolStub;
    private CellStub _poolManagerStub;
    private CellStub _billingStub;

    private int _moverTimeout = 180000;

    private PnfsHandler _pnfs;

    private String _ioQueue;

    private Map<UUID, DirlistRequestHandler> _requestHandlers =
        new ConcurrentHashMap<UUID, DirlistRequestHandler>();

    private ScheduledExecutorService _dirlistTimeoutExecutor;

    /**
     * Transfers with a mover.
     */
    private final Map<Integer,XrootdTransfer> _transfers =
        new ConcurrentHashMap<Integer,XrootdTransfer>();

    public static CellVersion getStaticCellVersion()
    {
        return new CellVersion(diskCacheV111.util.Version.getVersion(),
                               "$Revision: 11646 $");
    }

    public void setPoolStub(CellStub stub)
    {
        _poolStub = stub;
    }

    public void setPoolManagerStub(CellStub stub)
    {
        _poolManagerStub = stub;
    }

    public void setBillingStub(CellStub stub)
    {
        _billingStub = stub;
    }

    /**
     * Converts a colon separated list of paths to a List of FsPath.
     */
    private List<FsPath> toFsPaths(String s)
    {
        List<FsPath> list = new ArrayList();
        for (String path: s.split(":")) {
            list.add(new FsPath(path));
        }
        return list;
    }

    /**
     * The list of paths which are authorized for xrootd write access.
     */
    public void setWritePaths(String s)
    {
        _writePaths = toFsPaths(s);
    }

    /**
     * Returns the list of write paths.
     *
     * Notice that the getter uses a different property name than the
     * setter. This is because the getter returns a different type
     * than set by the setter, and hence we must not use the same
     * property name (otherwise Spring complains).
     */
    public List<FsPath> getWritePathsList()
    {
        return _writePaths;
    }

    /**
     * The list of paths which are authorized for xrootd write access.
     */
    public void setReadPaths(String s)
    {
        _readPaths = toFsPaths(s);
    }

    /**
     * Returns the list of read paths.
     *
     * Notice that the getter uses a different property name than the
     * setter. This is because the getter returns a different type
     * than set by the setter, and hence we must not use the same
     * property name (otherwise Spring complains).
     */
    public List<FsPath> getReadPathsList()
    {
        return _readPaths;
    }

    /**
     *
     */
    public void setAuthorizationFactory(AbstractAuthorizationFactory factory)
    {
        _authzFactory = factory;
    }

    public AbstractAuthorizationFactory getAuthorizationFactory()
    {
        return _authzFactory;
    }

    public void setPnfsHandler(PnfsHandler pnfs)
    {
        _pnfs = pnfs;
    }

    /**
     * The actual mover queue on the pool onto which this request gets
     * scheduled.
     */
    public void setIoQueue(String ioQueue)
    {
        _ioQueue = ioQueue;
    }

    public String getIoQueue()
    {
        return _ioQueue;
    }

    @Required
    public void setLoginStrategy(LoginStrategy strategy) {
        _strategy = strategy;
    }

    /**
     * Returns the mover timeout in milliseconds.
     */
    public int getMoverTimeout()
    {
        return _moverTimeout;
    }

    /**
     * The mover timeout is the time we wait for the mover to start
     * after having been enqueued.
     *
     * @param timeout The mover timeout in milliseconds
     */
    public void setMoverTimeout(int timeout)
    {
        if (timeout <= 0) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        _moverTimeout = timeout;
    }

    /**
     * Sets the ScheduledExecutorService used for periodic tasks.
     */
    public void setExecutor(ScheduledExecutorService executor)
    {
        executor.scheduleAtFixedRate(new PingMoversTask(_transfers.values()),
                                     PING_DELAY, PING_DELAY,
                                     TimeUnit.MILLISECONDS);
    }

    public void setDirlistTimeoutExecutor(ScheduledExecutorService executor)
    {
        _dirlistTimeoutExecutor = executor;
    }

    /**
     * Performs component initialization. Must be called after all
     * dependencies have been injected.
     */
    public void init()
    {
        _cellName = getCellName();
        _domainName = getCellDomainName();
    }

    @Override
    public void getInfo(PrintWriter pw)
    {
        pw.println(String.format("Protocol Version %d.%d",
                                 XROOTD_PROTOCOL_MAJOR_VERSION,
                                 XROOTD_PROTOCOL_MINOR_VERSION));
    }

    /**
     * Forms a full PNFS path. The path is created by concatenating
     * the root path and path. The root path is guaranteed to be a
     * prefix of the path returned.
     */
    private FsPath createFullPath(String path, FsPath rootPath)
    {
        return new FsPath(rootPath, new FsPath(path));
    }

    private XrootdTransfer
        createTransfer(InetSocketAddress client, FsPath path, long checksum,
                       UUID uuid, InetSocketAddress local, Subject subject)
    {
        XrootdTransfer transfer =
            new XrootdTransfer(_pnfs, subject, path);
        transfer.setCellName(_cellName);
        transfer.setDomainName(_domainName);
        transfer.setPoolManagerStub(_poolManagerStub);
        transfer.setPoolStub(_poolStub);
        transfer.setBillingStub(_billingStub);
        transfer.setClientAddress(client);
        transfer.setChecksum(checksum);
        transfer.setUUID(uuid);
        transfer.setDoorAddress(local);
        transfer.setFileHandle(_handleCounter.getAndIncrement());
        return transfer;
    }

    public XrootdTransfer
        read(InetSocketAddress client, String path, long checksum, UUID uuid,
             InetSocketAddress local, Subject subject, FsPath rootPath)
        throws CacheException, InterruptedException
    {
        FsPath fullPath = createFullPath(path, rootPath);

        if (!isReadAllowed(fullPath)) {
            throw new PermissionDeniedCacheException("Write permission denied");
        }

        XrootdTransfer transfer = createTransfer(client, fullPath, checksum,
                                                 uuid, local, subject);
        int handle = transfer.getFileHandle();

        InetSocketAddress address = null;
        _transfers.put(handle, transfer);
        try {
            transfer.readNameSpaceEntry();

            do {
                transfer.selectPool();
                try {
                    transfer.startMover(_ioQueue);
                    address = transfer.waitForRedirect(_moverTimeout);
                    if (address == null) {
                        _log.error("Pool failed to open TCP socket");
                    }
                } catch (CacheException e) {
                    _log.warn("Pool error: " + e.getMessage());
                }
            } while (address == null);

            transfer.setStatus("Mover " + transfer.getPool() + "/" +
                               transfer.getMoverId() + ": Sending");
        } catch (CacheException e) {
            transfer.notifyBilling(e.getRc(), e.getMessage());
            throw e;
        } catch (InterruptedException e) {
            transfer.notifyBilling(CacheException.UNEXPECTED_SYSTEM_EXCEPTION,
                                   "Transfer interrupted");
            throw e;
        } catch (RuntimeException e) {
            transfer.notifyBilling(CacheException.UNEXPECTED_SYSTEM_EXCEPTION,
                                   e.toString());
            throw e;
        } finally {
            if (address == null) {
                _transfers.remove(handle);
            }
        }
        return transfer;
    }

    public XrootdTransfer
        write(InetSocketAddress client, String path, long checksum, UUID uuid,
              boolean createDir, boolean overwrite,
              InetSocketAddress local, Subject subject, FsPath rootPath)
        throws CacheException, InterruptedException
    {
        FsPath fullPath = createFullPath(path, rootPath);

        if (!isWriteAllowed(fullPath)) {
            throw new PermissionDeniedCacheException("Write permission denied");
        }

        XrootdTransfer transfer = createTransfer(client, fullPath, checksum,
                                                 uuid, local, subject);
        transfer.setOverwriteAllowed(overwrite);
        int handle = transfer.getFileHandle();
        InetSocketAddress address = null;
        _transfers.put(handle, transfer);
        try {
            if (createDir) {
                transfer.createNameSpaceEntryWithParents();
            } else {
                transfer.createNameSpaceEntry();
            }
            try {
                do {
                    transfer.selectPool();
                    try {
                        transfer.startMover(_ioQueue);
                    } catch (TimeoutCacheException e) {
                        throw e;
                    } catch (CacheException e) {
                        _log.warn("Pool error: {}", e.getMessage());
                    }
                } while (!transfer.hasMover());

                address = transfer.waitForRedirect(_moverTimeout);
                if (address == null) {
                    throw new CacheException(transfer.getPool() + " failed to open TCP socket");
                }

                transfer.setStatus("Mover " + transfer.getPool() + "/" +
                                   transfer.getMoverId() + ": Receiving");
            } finally {
                if (address == null) {
                    transfer.deleteNameSpaceEntry();
                }
            }
        } catch (CacheException e) {
            transfer.notifyBilling(e.getRc(), e.getMessage());
            throw e;
        } catch (InterruptedException e) {
            transfer.notifyBilling(CacheException.UNEXPECTED_SYSTEM_EXCEPTION,
                                   "Transfer interrupted");
            throw e;
        } catch (RuntimeException e) {
            transfer.notifyBilling(CacheException.UNEXPECTED_SYSTEM_EXCEPTION,
                                   e.toString());
            throw e;
        } finally {
            if (address == null) {
                _transfers.remove(handle);
            }
        }
        return transfer;
    }

    /**
     * Delete the file denoted by path from the namespace
     *
     * @param path The path of the file that is going to be deleted
     * @throws CacheException Deletion of the file failed
     * @throws PermissionDeniedCacheException Caller does not have permission to delete the file
     */
    public void deleteFile(String path, Subject subject, FsPath rootPath)
        throws PermissionDeniedCacheException, CacheException
    {
        PnfsHandler pnfsHandler = new PnfsHandler(_pnfs, subject);

        /* create full path from given deletion path, because the door can
         * export partial results
         */
        FsPath fullPath = createFullPath(path, rootPath);

        if (!isWriteAllowed(fullPath)) {
            throw new PermissionDeniedCacheException("Write permission denied");
        }

        Set<FileType> allowedSet = EnumSet.of(FileType.REGULAR);
        pnfsHandler.deletePnfsEntry(fullPath.toString(), allowedSet);
    }

    /**
     * Delete the directory denoted by path from the namespace
     *
     * @param path The path of the directory that is going to be deleted
     * @throws CacheException
     */
    public void deleteDirectory(String path,
                                Subject subject,
                                FsPath rootPath) throws CacheException
    {
        PnfsHandler pnfsHandler = new PnfsHandler(_pnfs, subject);

        FsPath fullPath = createFullPath(path, rootPath);

        if (!isWriteAllowed(fullPath)) {
            throw new PermissionDeniedCacheException("Write permission denied");
        }

        Set<FileType> allowedSet = EnumSet.of(FileType.DIR);
        pnfsHandler.deletePnfsEntry(fullPath.toString(), allowedSet);
    }

    /**
     * Create the directory denoted by path in the namespace.
     *
     * @param path The path of the directory that is going to be created.
     * @param createParents Indicates whether the parent directories of the
     *        directory should be created automatically if they do not yet
     *        exist.
     * @throws CacheException Creation of the directory failed.
     */
    public void createDirectory(String path,
                                boolean createParents,
                                Subject subject,
                                FsPath rootPath)
                                                    throws CacheException
    {
        PnfsHandler pnfsHandler = new PnfsHandler(_pnfs, subject);

        FsPath fullPath = createFullPath(path, rootPath);

        if (!isWriteAllowed(fullPath)) {
            throw new PermissionDeniedCacheException("Write permission denied");
        }

        if (createParents) {
            pnfsHandler.createDirectories(fullPath);
        } else {
            pnfsHandler.createPnfsDirectory(fullPath.toString());
        }
    }

    /**
     * Emulate a file-move-operation by renaming sourcePath to targetPath in
     * the namespace
     * @param sourcePath the original path of the file that should be moved
     * @param targetPath the path to which the file should be moved
     * @throws CacheException
     */
    public void moveFile(String sourcePath,
                         String targetPath,
                         Subject subject,
                         FsPath rootPath) throws CacheException
    {
        PnfsHandler pnfsHandler = new PnfsHandler(_pnfs, subject);

        FsPath fullTargetPath = createFullPath(targetPath, rootPath);
        FsPath fullSourcePath = createFullPath(sourcePath, rootPath);

        if (!isWriteAllowed(fullSourcePath)) {
            throw new PermissionDeniedCacheException("No write permission on" +
                                                     " source path!");
        }

        if (!isWriteAllowed(fullTargetPath)) {
            throw new PermissionDeniedCacheException("No write permission on" +
                                                     " target path!");
        }

        pnfsHandler.renameEntry(fullSourcePath.toString(),
                                fullTargetPath.toString(),
                                false);
    }

    /**
     * List the contents of a path, usually a directory. In order to make
     * fragmented responses, as supported by the xrootd protocol, possible and
     * not block the processing thread in the door, this will register the
     * passed callback along with the UUID of the message that is sent to
     * PNFS-manager.
     *
     * Once PNFS-manager replies to the message, that callback is retrieved and
     * the response is processed by the callback.
     *
     * @param path The path that is listed
     * @param callback The callback that will process the response
     * @throws PermissionDeniedCacheException
     * @throws CacheException Listing message can not be routed to PnfsManager.
     */
    public void listPath(String path,
                         Subject subject,
                         MessageCallback<PnfsListDirectoryMessage> callback)
        throws PermissionDeniedCacheException
    {
        PnfsHandler pnfsHandler = new PnfsHandler(_pnfs, subject);
        PnfsListDirectoryMessage msg =
            new PnfsListDirectoryMessage(path, null, null,
                                         EnumSet.noneOf(FileAttribute.class));
        UUID uuid = msg.getUUID();

        try {
            DirlistRequestHandler requestHandler =
                new DirlistRequestHandler(uuid,
                                          pnfsHandler.getPnfsTimeout(),
                                          callback);
            _requestHandlers.put(uuid, requestHandler);
            pnfsHandler.send(msg);
            requestHandler.resetTimeout();
        } catch (NoRouteToCellException nrtce) {
            _requestHandlers.remove(uuid);
            callback.noroute();
        } catch (RejectedExecutionException ree) {
            _requestHandlers.remove(uuid);
            callback.failure(CacheException.UNEXPECTED_SYSTEM_EXCEPTION,
                             ree.getMessage());
        }
    }

    /**
     * Encapsulate the list directory callback into a handler that manages the
     * scheduled executor service for the timeout handling.
     *
     */
    private class DirlistRequestHandler {
        private ScheduledFuture<?> _executionInstance;
        private final long _timeout;
        private final UUID _uuid;
        private final MessageCallback<PnfsListDirectoryMessage> _callback;

        public DirlistRequestHandler(UUID uuid,
                              long responseTimeout,
                              MessageCallback<PnfsListDirectoryMessage> callback) {
            _uuid = uuid;
            _timeout = responseTimeout;
            _callback = callback;
        }

        /**
         * Final listing result. Report back via callback and cancel
         * the timeout handler.
         * @param msg The reply containing the listing result.
         */
        public synchronized void success(PnfsListDirectoryMessage msg) {
            if (_requestHandlers.remove(_uuid) == this) {
                cancelTimeout();
                _callback.success(msg);
            }
        }

        /**
         * Partial listing result, report that back to the callback. Also,
         * reset the timeout timer in anticipation of further listing results.
         * @param msg The reply containing the partial directory listing.
         */
        public synchronized void continueListing(PnfsListDirectoryMessage msg) {
            try {
                _callback.success(msg);
                resetTimeout();
            } catch (RejectedExecutionException ree) {
                _requestHandlers.remove(_uuid);
                _callback.failure(CacheException.UNEXPECTED_SYSTEM_EXCEPTION,
                                  ree.getMessage());
            }
        }

        /**
         * Remove the request handler from the list, report a failure to the
         * callback and cancel the timeout timer.
         * @param msg The reply received from PNFS
         */
        public synchronized void failure(PnfsListDirectoryMessage msg) {
            if (_requestHandlers.remove(_uuid) == this) {
                cancelTimeout();
                _callback.failure(msg.getReturnCode(), msg.getErrorObject());
            }
        }

        /**
         * Reschedule the timeout task with the same timeout as initially.
         * Rescheduling means cancelling the old task and submitting a new one.
         * @throws RejectedExecutionException
         */
        public synchronized void resetTimeout()
            throws RejectedExecutionException {
            Runnable target = new Runnable() {
                public void run() {
                    if (_requestHandlers.remove(_uuid)
                            == DirlistRequestHandler.this) {
                        _callback.timeout();
                    }
                }
            };

            if (_executionInstance != null) {
                _executionInstance.cancel(false);
            }

            _executionInstance =
                _dirlistTimeoutExecutor.schedule(target,
                                                 _timeout,
                                                 TimeUnit.MILLISECONDS);
        }

        public synchronized void cancelTimeout() {
            _executionInstance.cancel(false);
        }
    }

    /**
     * Check whether the given path matches against a list of allowed
     * write paths.
     *
     * @param path the path which is going to be checked
     */
    private boolean isWriteAllowed(FsPath path)
    {
        for (FsPath prefix: _writePaths) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check whether the given path matches against a list of allowed
     * read paths.
     *
     * @param path the path which is going to be checked
     */
    private boolean isReadAllowed(FsPath path)
    {
        for (FsPath prefix: _readPaths) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private Inet4Address getFirstIpv4(Collection<NetIFContainer> interfaces)
    {
        for (NetIFContainer container: interfaces) {
            for (Object ip: container.getInetAddresses()) {
                if (ip instanceof Inet4Address) {
                    return (Inet4Address) ip;
                }
            }
        }
        return null;
    }

    public void messageArrived(XrootdDoorAdressInfoMessage msg)
    {
        _log.debug("Received redirect msg from mover");

        XrootdTransfer transfer = _transfers.get(msg.getXrootdFileHandle());

        if (transfer != null) {
            transfer.setUUIDSupported(msg.isUUIDEnabledPool());
            // REVISIT: pick the first IPv4 address from the
            // collection at this point, we can't determine, which of
            // the pool IP-addresses is the right one, so we select
            // the first
            Collection<NetIFContainer> interfaces =
                Collections.checkedCollection(msg.getNetworkInterfaces(),
                                              NetIFContainer.class);
            Inet4Address ip = getFirstIpv4(interfaces);

            if (ip != null) {
                InetSocketAddress address =
                    new InetSocketAddress(ip, msg.getServerPort());
                transfer.redirect(address);
            } else {
                _log.warn("No valid IP-address received from pool. Redirection not possible");
                transfer.redirect(null);
            }
        }
    }

    public void messageArrived(DoorTransferFinishedMessage msg)
    {
        if ((msg.getProtocolInfo() instanceof XrootdProtocolInfo)) {
            XrootdProtocolInfo info =
                (XrootdProtocolInfo) msg.getProtocolInfo();
            XrootdTransfer transfer =
                _transfers.remove(info.getXrootdFileHandle());
            if (transfer != null) {
                transfer.finished(msg);

                int rc = msg.getReturnCode();
                if (rc == 0) {
                    transfer.notifyBilling(0, "");
                    _log.info("Transfer {}@{} finished",
                              msg.getPnfsId(), msg.getPoolName());

                } else {
                    transfer.notifyBilling(rc, msg.getErrorObject().toString());
                    _log.info("Transfer {}@{} failed: {} (error code={})",
                              new Object[] {msg.getPnfsId(), msg.getPoolName(),
                                            msg.getErrorObject(), rc});
                }
            }
        } else {
            _log.warn("Ignoring unknown protocol info {} from pool {}",
                      msg.getProtocolInfo(), msg.getPoolName());
        }
    }

    /**
     * Try to find callback registered in listPath(...) and process the
     * response there
     * @param msg The reply to a PnfsListDirectoryMessage sent earlier.
     */
    public void messageArrived(PnfsListDirectoryMessage msg)
    {
        UUID uuid = msg.getUUID();
        DirlistRequestHandler request = _requestHandlers.get(uuid);

        if (request == null) {
            _log.info("Did not find the callback for directory listing " +
                      "message with UUID {}.", uuid);
            return;
        }

        if (msg.getReturnCode() == 0 && msg.isFinal()) {
            request.success(msg);
        } else if (msg.getReturnCode() == 0) {
            request.continueListing(msg);
        } else {
            request.failure(msg);
        }
    }

    public FileMetaData getFileMetaData(String path,
                                        Subject subject,
                                        FsPath rootPath) throws CacheException
    {
        return getFileMetaData(createFullPath(path, rootPath), subject);
    }

    private FileMetaData getFileMetaData(FsPath fullPath,
                                         Subject subject) throws CacheException
    {
        PnfsHandler pnfsHandler = new PnfsHandler(_pnfs, subject);
        return new FileMetaData(pnfsHandler.getFileAttributes(fullPath.toString(),
                                                              FileMetaData.getKnownFileAttributes()));
    }

    public FileMetaData[] getMultipleFileMetaData(String[] allPaths,
                                                  Subject subject,
                                                  FsPath rootPath)
        throws CacheException
    {
        FileMetaData[] allMetas = new FileMetaData[allPaths.length];

        // TODO: Use SpreadAndWait
        for (int i = 0; i < allPaths.length; i++) {
            try {
                allMetas[i] = getFileMetaData(allPaths[i], subject, rootPath);
            } catch (CacheException e) {
                if (e.getRc() != CacheException.FILE_NOT_FOUND &&
                    e.getRc() != CacheException.NOT_IN_TRASH) {
                    throw e;
                }
            }
        }
        return allMetas;
    }

    /**
     * Use login strategy to obtain session information about the provided
     * subject
     *    - root directory
     *    - is access read only
     *    - subject adorned with further information
     *
     * @param subject Subject which should be logged in
     * @throws CacheException Logging in the supplied subject did not work
     */
    public LoginReply login(Subject subject)
        throws CacheException {
            return _strategy.login(subject);
    }

    /**
     * To allow the transfer monitoring in the httpd cell to recognize us
     * as a door, we have to emulate LoginManager.  To emulate
     * LoginManager we list ourselves as our child.
     */
    public final static String hh_get_children = "[-binary]";
    public Object ac_get_children(Args args)
    {
        boolean binary = args.getOpt("binary") != null;
        if (binary) {
            String [] list = new String[] { _cellName };
            return new LoginManagerChildrenInfo(_cellName, _domainName, list);
        } else {
            return _cellName;
        }
    }

    public final static String hh_get_door_info = "[-binary]";
    public final static String fh_get_door_info =
        "Provides information about the door and current transfers";
    public Object ac_get_door_info(Args args)
    {
        List<IoDoorEntry> entries = new ArrayList<IoDoorEntry>();
        for (Transfer transfer: _transfers.values()) {
            entries.add(transfer.getIoDoorEntry());
        }

        IoDoorInfo doorInfo = new IoDoorInfo(_cellName, _domainName);
        doorInfo.setProtocol(XROOTD_PROTOCOL_STRING, XROOTD_PROTOCOL_VERSION);
        doorInfo.setOwner("");
        doorInfo.setProcess("");
        doorInfo.setIoDoorEntries(entries.toArray(new IoDoorEntry[0]));
        return (args.getOpt("binary") != null) ? doorInfo : doorInfo.toString();
    }
}
