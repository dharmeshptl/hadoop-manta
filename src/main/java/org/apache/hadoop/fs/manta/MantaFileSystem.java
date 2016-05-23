package org.apache.hadoop.fs.manta;

import com.google.common.annotations.VisibleForTesting;
import com.joyent.manta.client.MantaClient;
import com.joyent.manta.client.MantaDirectoryListingIterator;
import com.joyent.manta.client.MantaHttpHeaders;
import com.joyent.manta.client.MantaObject;
import com.joyent.manta.client.MantaObjectOutputStream;
import com.joyent.manta.client.MantaObjectResponse;
import com.joyent.manta.client.MantaSeekableByteChannel;
import com.joyent.manta.config.ChainedConfigContext;
import com.joyent.manta.config.ConfigContext;
import com.joyent.manta.config.SystemSettingsConfigContext;
import com.joyent.manta.exception.MantaClientHttpResponseException;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FSInputStream;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.util.Progressable;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.function.Function;

/**
 * This class is a <a href="https://github.com/joyent/manta">Manta object store</a>
 * remote file system implementation for Hadoop. It allows you to stream files
 * directly from a Manta object store.
 */
@InterfaceAudience.Public
public class MantaFileSystem extends FileSystem implements AutoCloseable {
    /**
     * Logger instance.
     */
    public static final Logger LOG =
            LoggerFactory.getLogger(MantaFileSystem.class);

    /**
     * Alias for the home directory in Manta.
     */
    public static final Path HOME_ALIAS_PATH = new Path("~~");

    /**
     * The root Manta URI used to identify the filesystem.
     */
    private static final URI ROOT_MANTA_URI = URI.create("manta:///");

    /**
     * Path to the current working directory.
     */
    private volatile Path workingDir;

    /**
     * Manta SDK configuration object.
     */
    private ConfigContext config;

    /**
     * Manta client.
     */
    private MantaClient client;

    static {
        /* Log class load in order to provide debugging information to
         * users that are attempting to embed the library.
         */
        LOG.debug("Manta filesystem class loaded");
    }

    /**
     * Create a new instance.
     */
    public MantaFileSystem() {
        super();
    }

    /**
     * Method used for initializing the class directly with the Manta SDK
     * configuration. This is used for testing.
     *
     * @param name a uri whose authority section names the host, port, etc.
     *             for this FileSystem. [Not used for Manta implementation]
     * @param customConfig custom configuration
     * @throws IOException thrown when we can't create a Manta Client
     */
    @VisibleForTesting
    void initialize(final URI name, final ConfigContext customConfig) throws IOException {
        this.config = customConfig;
        this.client = new MantaClient(customConfig);
    }

    /**
     * Initialization method called after a new FileSystem instance is constructed.
     *
     * @param name a uri whose authority section names the host, port, etc.
     *             for this FileSystem. [Not used for Manta implementation]
     * @param conf Hadoop configuration object
     * @throws IOException thrown when we can't create a Manta Client
     */
    @Override
    public void initialize(final URI name, final Configuration conf) throws IOException {
        super.initialize(name, conf);

        ChainedConfigContext chained = new ChainedConfigContext(
                new SystemSettingsConfigContext(),
                new HadoopConfigurationContext(conf)
        );

        this.config = chained;
        this.client = new MantaClient(this.config);

        this.workingDir = getInitialWorkingDirectory();
    }

    /**
     * Return the protocol scheme for the FileSystem.
     *
     * @return "manta"
     */
    public String getScheme() {
        return "manta";
    }

    @Override
    public URI getUri() {
        return this.ROOT_MANTA_URI;
    }

    @Override
    public FSDataInputStream open(final Path path, final int i) throws IOException {
        LOG.debug("Opening '{}' for reading.", path);

        final FileStatus fileStatus = getFileStatus(path);

        if (fileStatus.isDirectory()) {
            final String msg = String.format("Can't open %s because it is a directory", path);
            throw new FileNotFoundException(msg);
        }

        String mantaPath = mantaPath(path);

        MantaSeekableByteChannel channel = client.getSeekableByteChannel(mantaPath);
        FSInputStream fsInput = new MantaSeekableInputStream(channel);

        return new FSDataInputStream(fsInput);
    }

    @Override
    public FSDataOutputStream create(final Path path, final FsPermission fsPermission,
                                     final boolean overwrite,
                                     final int bufferSize,
                                     final short replication,
                                     final long blockSize,
                                     final Progressable progressable) throws IOException {
        String mantaPath = mantaPath(path);

        if (!overwrite && client.existsAndIsAccessible(mantaPath)) {
            String msg = String.format("File already exists at path: %s", path);
            throw new FileAlreadyExistsException(msg);
        }

        MantaHttpHeaders headers = new MantaHttpHeaders();

        if (replication > 0) {
            headers.setDurabilityLevel(replication);
        }

        LOG.debug("Creating new file with {} replicas at path: {}", replication, path);

        MantaObjectOutputStream out = client.putAsOutputStream(mantaPath, headers);
        return new FSDataOutputStream(out, statistics);
    }

    @Override
    public FSDataOutputStream append(final Path path, final int i,
                                     final Progressable progressable) throws IOException {
        throw new IOException("Not supported");
    }

    @Override
    public boolean rename(final Path original, final Path newName) throws IOException {
        // We alias the semantics for moving a object and expose it as rename
        return move(original, newName);
    }

    @Override
    public boolean delete(final Path path, final boolean recursive) throws IOException {
        String mantaPath = mantaPath(path);

        // We don't bother deleting something that doesn't exist

        final MantaObjectResponse head;

        try {
             head = client.head(mantaPath);
        } catch (MantaClientHttpResponseException e) {
            if (e.getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                return false;
            }

            throw e;
        }

        if (recursive && head.isDirectory()) {
            LOG.debug("Recursively deleting path: {}", mantaPath);
            client.deleteRecursive(mantaPath);
        } else {
            LOG.debug("Deleting path: {}", mantaPath);
            client.delete(mantaPath);
        }

        return !client.existsAndIsAccessible(mantaPath);
    }

    @Override
    public FileStatus[] listStatus(final Path path) throws FileNotFoundException, IOException {
        LOG.debug("List status for path: {}", path);
        String mantaPath = mantaPath(path);

        if (!client.existsAndIsAccessible(mantaPath)) {
            throw new FileNotFoundException(mantaPath);
        }

        return client.listObjects(mantaPath)
                .map((Function<MantaObject, FileStatus>) MantaFileStatus::new)
                .toArray(FileStatus[]::new);
    }

    @Override
    protected RemoteIterator<LocatedFileStatus> listLocatedStatus(
            final Path path, final PathFilter filter) throws FileNotFoundException, IOException {
        LOG.debug("List located status for path: {}", path);

        String mantaPath = mantaPath(path);

        if (!client.existsAndIsAccessible(mantaPath)) {
            throw new FileNotFoundException(mantaPath);
        }

        MantaDirectoryListingIterator itr = client.streamingIterator(mantaPath);
        return new MantaRemoteIterator(filter, itr, path, this, true);
    }

    @Override
    public void setWorkingDirectory(final Path path) {
        this.workingDir = path;
    }

    @Override
    public Path getWorkingDirectory() {
        return this.workingDir;
    }

    @Override
    public Path getHomeDirectory() {
        return new Path(config.getMantaHomeDirectory());
    }

    @Override
    protected Path getInitialWorkingDirectory() {
        return new Path(config.getMantaHomeDirectory());
    }

    @Override
    public boolean mkdirs(final Path path, final FsPermission fsPermission) throws IOException {
        String mantaPath = mantaPath(path);
        return client.putDirectory(mantaPath);
    }

    @Override
    public FileStatus getFileStatus(final Path path) throws IOException {
        String mantaPath = mantaPath(path);
        LOG.debug("Getting path status for: {}", mantaPath);

        final MantaObjectResponse response;

        try {
            response = client.head(mantaPath);
        } catch (MantaClientHttpResponseException e) {
            if (e.getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                throw new FileNotFoundException(mantaPath);
            }

            throw e;
        }

        MantaFileStatus status = new MantaFileStatus(response, path);

        return status;
    }

    @Override
    public boolean exists(final Path path) throws IOException {
        return client.existsAndIsAccessible(mantaPath(path));
    }

    @Override
    public boolean isDirectory(final Path path) throws IOException {
        try {
            return client.head(mantaPath(path)).isDirectory();
        } catch (MantaClientHttpResponseException e) {
            /* We imitate the behavior of FileSystem.isDirectory, by changing a
             * FileNotFoundException into a false return value. */
            if (e.getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                return false;
            }

            throw e;
        }
    }

    @Override
    public boolean isFile(final Path path) throws IOException {
        return !isDirectory(path);
    }

    @Override
    public boolean truncate(final Path path, final long newLength) throws IOException {
        final String mantaPath = mantaPath(path);

        final String contentType;

        try {
            MantaObject head = client.head(mantaPath);
            contentType = head.getContentType();
        } catch (MantaClientHttpResponseException e) {
            if (e.getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                throw new FileNotFoundException(mantaPath);
            }

            throw e;
        }

        if (newLength == 0) {
            MantaHttpHeaders headers = new MantaHttpHeaders()
                    .setContentType(contentType);

            client.put(mantaPath, "", headers, null);
            return true;
        }

        throw new UnsupportedOperationException("Truncating to an arbitrary length higher "
                + "than zero is not supported at this time");
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            client.closeQuietly();
        }
    }

    /**
     * Moves an object from one path to another.
     * @param original path to move from
     * @param newName path to move to
     * @return true if moved successfully
     * @throws IOException thrown when we can't move paths
     */
    public boolean move(final Path original, final Path newName) throws IOException {
        String source = mantaPath(original);
        String destination = mantaPath(newName);

        if (!client.existsAndIsAccessible(source)) {
            throw new FileNotFoundException(source);
        }

        LOG.debug("Moving [{}] to [{}]", original, newName);

        client.move(source, destination);

        return client.existsAndIsAccessible(destination);
    }

    /**
     * Converts a Hadoop {@link Path} object to a path String that the Manta
     * client understands.
     *
     * @param path Hadoop path object to convert
     * @return String representation of path on Manta
     */
    private String mantaPath(final Path path) {
        final String mantaPath;

        if (path.getParent() == null) {
            mantaPath = path.toString();
        } else if (path.getParent().equals(HOME_ALIAS_PATH)) {
            String base = StringUtils.removeStart(path.toString().substring(2), "/");

            mantaPath = String.format("%s/%s", config.getMantaHomeDirectory(), base);
        } else if (getWorkingDirectory() != null) {
            mantaPath = new Path(getWorkingDirectory(), path).toString();
        } else {
            throw new IllegalArgumentException(String.format("Invalid path: %s", path));
        }

        return StringUtils.removeStart(mantaPath, "manta:");
    }

    /**
     * Package private visibility method for getting the Manta SDK client
     * used for testing.
     *
     * @return reference to the backing Manta client
     */
    @VisibleForTesting
    MantaClient getMantaClient() {
        return this.client;
    }

    /**
     * Package private visibility method for getting the Manta SDK configuration
     * used for test.
     *
     * @return reference to the backing Manta SDK configuration
     */
    @VisibleForTesting
    ConfigContext getConfig() {
        return this.config;
    }
}
