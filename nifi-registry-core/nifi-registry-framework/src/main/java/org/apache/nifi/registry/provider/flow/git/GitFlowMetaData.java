/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.registry.provider.flow.git;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static org.apache.commons.lang3.StringUtils.isEmpty;

class GitFlowMetaData {

    static final int CURRENT_LAYOUT_VERSION = 1;

    static final String LAYOUT_VERSION = "layoutVer";
    static final String BUCKET_ID = "bucketId";
    static final String FLOWS = "flows";
    static final String VER = "ver";
    static final String FILE = "file";
    static final String FLOW_NAME = "flowName";
    static final String FLOW_DESC = "flowDesc";
    static final String AUTHOR = "author";
    static final String COMMENTS = "comments";
    static final String CREATED = "created";
    static final String BUCKET_FILENAME = "bucket.yml";

    private static final Logger logger = LoggerFactory.getLogger(GitFlowMetaData.class);

    private Repository gitRepo;
    private String remoteToPush;
    private CredentialsProvider credentialsProvider;
    private ScheduledExecutorService executorService;

    private final BlockingQueue<Long> pushQueue = new ArrayBlockingQueue<>(1);

    /**
     * Bucket ID to Bucket.
     */
    private Map<String, Bucket> buckets = new HashMap<>();

    public void setRemoteToPush(String remoteToPush) {
        this.remoteToPush = remoteToPush;
    }

    public void setRemoteCredential(String userName, String password) {
        this.credentialsProvider = new UsernamePasswordCredentialsProvider(userName, password);
    }

    /**
     * Open a Git repository using the specified directory.
     * @param gitProjectRootDir a root directory of a Git project
     * @return created Repository
     * @throws IOException thrown when the specified directory does not exist,
     *                     does not have read/write privilege or not containing .git directory
     */
    private Repository openRepository(final File gitProjectRootDir, Boolean enforceRecreate) throws IOException {
        if (this.gitRepo != null && !enforceRecreate) {
            return this.gitRepo;
        }

        final FileRepositoryBuilder builder = createRepositoryBuilder(gitProjectRootDir);

        if (builder.getGitDir() == null) {
            throw new IOException(format("Directory '%s' does not contain a .git directory." +
                            " Please init and configure the directory with 'git init' command before using it from NiFi Registry.",
                    gitProjectRootDir));
        }

        return builder.build();
    }

    private FileRepositoryBuilder createRepositoryBuilder(File gitProjectRootDir) throws IOException {
        // Instead of using FileUtils.ensureDirectoryExistAndCanReadAndWrite, check availability manually here.
        // Because the util will try to create a dir if not exist.
        // The git dir should be initialized and configured by users.
        if (!gitProjectRootDir.isDirectory()) {
            throw new IOException(format("'%s' is not a directory or does not exist.", gitProjectRootDir));
        }

        if (!(gitProjectRootDir.canRead() && gitProjectRootDir.canWrite())) {
            throw new IOException(format("Directory '%s' does not have read/write privilege.", gitProjectRootDir));
        }

        // Search .git dir but avoid searching parent directories.
        return new FileRepositoryBuilder()
                .readEnvironment()
                .setMustExist(true)
                .addCeilingDirectory(gitProjectRootDir)
                .findGitDir(gitProjectRootDir);
    }


    public void resetGitRepository(File gitProjectRootDir) throws IOException, GitAPIException, InterruptedException {
        URI gitRepositoryUrl = URI.create("");
        if (this.isGitRepositoryExisting(gitProjectRootDir)) {
            gitRepo = openRepository(gitProjectRootDir, false);

            try (final Git git = new Git(gitRepo)) {
                TerminateWhenRemotePathDoesNotExist(git);

                if (!git.status().call().isClean()) {
                    throw new IOException("Directory '%s' contains changes. " +
                            "Therefore a complete reset of the repository is not possible.\n" +
                            "Please commit your changes and push to remote repository manually." +
                            "Git persistence provider does not recover from conflicting changes automatically.");
                }

                Optional<RemoteConfig> remoteConfig = git.remoteList().call()
                        .stream().filter(r -> r.getName().equalsIgnoreCase(remoteToPush)).findFirst();
                if (remoteConfig.isPresent() && !remoteConfig.get().getURIs().isEmpty()) {
                    URIish remoteUri = remoteConfig.get().getURIs().get(0);
                    if (remoteUri.getHost() == null) {
                        gitRepositoryUrl = new File(remoteUri.toString()).toURI();
                    } else {
                        gitRepositoryUrl = URI.create(remoteUri.toString());
                    }
                } else {
                    throw new IOException("Cannot find/derive a remote git repository uri. Please provide a valid " +
                            "remote origin by initializing your git repository correctly (for example: " +
                            "git remote add origin repositoryUri).");
                }
            }
        }

        closeRepository();

        File backupDir = deriveBackupDir(gitProjectRootDir);
        try {
            backupProjectDir(gitProjectRootDir, backupDir);
            FileUtils.deleteDirectory(gitProjectRootDir);
            cloneRepository(gitProjectRootDir, gitRepositoryUrl);
        } catch (Exception ex) {
            restoreProjectDir(gitProjectRootDir, backupDir);
            throw ex;
        } finally {
            safeDeleteDir(backupDir);
        }
    }

    private void safeDeleteDir(File dir) throws IOException {
        if (dir.exists()) {
            FileUtils.deleteDirectory(dir);
        }
    }

    private void restoreProjectDir(File gitProjectRootDir, File backupDir) throws IOException {
        safeDeleteDir(gitProjectRootDir);
        FileUtils.copyDirectory(backupDir, gitProjectRootDir);
    }

    private void backupProjectDir(File gitProjectRootDir, File backupDir) throws IOException {
        safeDeleteDir(backupDir);
        FileUtils.copyDirectory(gitProjectRootDir, backupDir);
    }

    private File deriveBackupDir(File gitProjectRootDir) {
        return new File(gitProjectRootDir.getParentFile(), "backup");
    }

    private void cloneRepository(File gitProjectRootDir, URI gitRepositoryUrl) throws GitAPIException {
        CloneCommand command = Git.cloneRepository()
                .setURI(gitRepositoryUrl.toString())
                .setDirectory(gitProjectRootDir);
        if (credentialsProvider != null) {
            command.setCredentialsProvider(credentialsProvider);
        }

        command.call().close();
    }

    public void closeRepository() throws InterruptedException {
        this.stopPushThread();
        if (gitRepo != null) {
            gitRepo.close();
        }
    }

    public void pullChanges(File gitProjectRootDir) throws IOException, GitAPIException {
        // TODO: prevent others from pushing during the pull command
        // TODO: wait until push thread has terminated
        gitRepo = openRepository(gitProjectRootDir, false);

        try (final Git git = new Git(gitRepo)) {
            TerminateWhenRemotePathDoesNotExist(git);

            if (!git.status().call().isClean()) {
                throw new IOException("Directory '%s' contains changes. " +
                        "Therefore a complete reset of the repository is not possible.\n" +
                        "Please commit your changes and push to remote repository.");
            }

            final PullCommand pullCommand = git.pull().setRemote(this.remoteToPush)
                    .setRemoteBranchName(gitRepo.getFullBranch());

            if (credentialsProvider != null) {
                pullCommand.setCredentialsProvider(credentialsProvider);
            }

            final PullResult pullResult = pullCommand.call();
            if (!pullResult.isSuccessful()) {
                final Ref ref = git.reset().setMode(ResetCommand.ResetType.HARD).call();
                logger.info("reset git repository to {}, because pull request was not successful.", ref.toString());
                throw new IOException(
                        format("The pull command was not successful because '%s'.", pullResult.toString()));
            } else {
                this.loadGitRepository(gitProjectRootDir);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void loadGitRepository(File gitProjectRootDir) throws IOException, GitAPIException {
        gitRepo = openRepository(gitProjectRootDir, false);

        try (final Git git = new Git(gitRepo)) {

            // Check if remote exists.
            TerminateWhenRemotePathDoesNotExist(git);

            boolean isLatestCommit = true;
            try {
                for (RevCommit commit : git.log().call()) {
                    final String shortCommitId = commit.getId().abbreviate(7).name();
                    logger.debug("Processing a commit: {}", shortCommitId);
                    final RevTree tree = commit.getTree();

                    try (final TreeWalk treeWalk = new TreeWalk(gitRepo)) {
                        treeWalk.addTree(tree);

                        // Path -> ObjectId
                        final Map<String, ObjectId> bucketObjectIds = new HashMap<>();
                        final Map<String, ObjectId> flowSnapshotObjectIds = new HashMap<>();
                        while (treeWalk.next()) {
                            if (treeWalk.isSubtree()) {
                                treeWalk.enterSubtree();
                            } else {
                                final String pathString = treeWalk.getPathString();
                                // TODO: what is this nth?? When does it get grater than 0? Tree count seems to be always 1..
                                if (pathString.endsWith("/" + BUCKET_FILENAME)) {
                                    bucketObjectIds.put(pathString, treeWalk.getObjectId(0));
                                } else if (pathString.endsWith(GitFlowPersistenceProvider.SNAPSHOT_EXTENSION)) {
                                    flowSnapshotObjectIds.put(pathString, treeWalk.getObjectId(0));
                                }
                            }
                        }

                        if (bucketObjectIds.isEmpty()) {
                            // No bucket.yml means at this point, all flows are deleted. No need to scan older commits because those are already deleted.
                            logger.debug("Tree at commit {} does not contain any " + BUCKET_FILENAME + ". Stop loading commits here.", shortCommitId);
                            return;
                        }

                        loadBuckets(gitRepo, commit, isLatestCommit, bucketObjectIds, flowSnapshotObjectIds);
                        isLatestCommit = false;
                    }
                }
            } catch (NoHeadException e) {
                logger.debug("'{}' does not have any commit yet. Starting with empty buckets.", gitProjectRootDir);
            }

        }
    }

    private void TerminateWhenRemotePathDoesNotExist(Git git) throws GitAPIException {
        if (!isEmpty(remoteToPush)) {
            final List<RemoteConfig> remotes = git.remoteList().call();
            final boolean isRemoteExist = remotes.stream().anyMatch(remote -> remote.getName().equals(remoteToPush));
            if (!isRemoteExist) {
                final List<String> remoteNames = remotes.stream().map(RemoteConfig::getName).collect(Collectors.toList());
                throw new IllegalArgumentException(
                        format("The configured remote '%s' to push does not exist. Available remotes are %s", remoteToPush, remoteNames));
            }
        }
    }

    void startPushThread() {
        // If successfully loaded, start pushing thread if necessary.
        if (isEmpty(remoteToPush)) {
            return;
        }

        final ThreadFactory threadFactory = new BasicThreadFactory.Builder()
                .daemon(true).namingPattern(getClass().getSimpleName() + " Push thread").build();

        // Use scheduled fixed delay to control the minimum interval between push activities.
        // The necessity of executing push is controlled by offering messages to the pushQueue.
        // If multiple commits are made within this time window, those are pushed by a single push execution.
        this.executorService = Executors.newSingleThreadScheduledExecutor(threadFactory);
        executorService.scheduleWithFixedDelay(() -> {

            final Long offeredTimestamp;
            try {
                offeredTimestamp = pushQueue.take();
            } catch (InterruptedException e) {
                logger.warn("Waiting for push request has been interrupted due to {}", e.getMessage(), e);
                return;
            }

            logger.debug("Took a push request sent at {} to {}...", offeredTimestamp, remoteToPush);
            final PushCommand pushCommand = new Git(gitRepo).push().setRemote(remoteToPush);
            if (credentialsProvider != null) {
                pushCommand.setCredentialsProvider(credentialsProvider);
            }

            try {
                final Iterable<PushResult> pushResults = pushCommand.call();
                for (PushResult pushResult : pushResults) {
                    logger.debug(pushResult.getMessages());
                }
            } catch (GitAPIException e) {
                logger.error(format("Failed to push commits to %s due to %s", remoteToPush, e), e);
            }

        }, 10, 10, TimeUnit.SECONDS);
    }

    void stopPushThread() throws InterruptedException {
        if(this.executorService == null)
            return;

        this.executorService.shutdown();
        // push latest changes
        this.pushQueue.offer(System.currentTimeMillis());
        while (!this.executorService.isTerminated()) {
            Thread.sleep(100);
        }
    }

    @SuppressWarnings("unchecked")
    private void loadBuckets(Repository gitRepo, RevCommit commit, boolean isLatestCommit, Map<String, ObjectId> bucketObjectIds, Map<String, ObjectId> flowSnapshotObjectIds) throws IOException {
        final Yaml yaml = new Yaml();
        for (String bucketFilePath : bucketObjectIds.keySet()) {
            final ObjectId bucketObjectId = bucketObjectIds.get(bucketFilePath);
            final Map<String, Object> bucketMeta;
            try (InputStream bucketIn = gitRepo.newObjectReader().open(bucketObjectId).openStream()) {
                bucketMeta = yaml.load(bucketIn);
            }

            if (!validateRequiredValue(bucketMeta, bucketFilePath, LAYOUT_VERSION, BUCKET_ID, FLOWS)) {
                continue;
            }

            int layoutVersion = (int) bucketMeta.get(LAYOUT_VERSION);
            if (layoutVersion > CURRENT_LAYOUT_VERSION) {
                logger.warn("{} has unsupported {} {}. This Registry can only support {} or lower. Skipping it.",
                        bucketFilePath, LAYOUT_VERSION, layoutVersion, CURRENT_LAYOUT_VERSION);
                continue;
            }

            final String bucketId = (String) bucketMeta.get(BUCKET_ID);

            final Bucket bucket;
            if (isLatestCommit) {
                // If this is the latest commit, then create one.
                bucket = getBucketOrCreate(bucketId);
            } else {
                // Otherwise non-existing bucket means it's already deleted.
                final Optional<Bucket> bucketOpt = getBucket(bucketId);
                if (bucketOpt.isPresent()) {
                    bucket = bucketOpt.get();
                } else {
                    logger.debug("Bucket {} does not exist any longer. It may have been deleted.", bucketId);
                    continue;
                }
            }

            // Since the bucketName is restored from pathname, it can be different from the original bucket name when it sanitized.
            final String bucketDirName = bucketFilePath.substring(0, bucketFilePath.lastIndexOf("/"));

            // Since commits are read in LIFO order, avoid old commits overriding the latest bucket name.
            if (isEmpty(bucket.getBucketDirName())) {
                bucket.setBucketDirName(bucketDirName);
            }

            final Map<String, Object> flows = (Map<String, Object>) bucketMeta.get(FLOWS);
            loadFlows(commit, isLatestCommit, bucket, bucketFilePath, flows, flowSnapshotObjectIds);
        }
    }

    @SuppressWarnings("unchecked")
    private void loadFlows(RevCommit commit, boolean isLatestCommit, Bucket bucket, String backetFilePath, Map<String, Object> flows, Map<String, ObjectId> flowSnapshotObjectIds) {
        for (String flowId : flows.keySet()) {
            final Map<String, Object> flowMeta = (Map<String, Object>) flows.get(flowId);

            if (!validateRequiredValue(flowMeta, backetFilePath + ":" + flowId, VER, FILE)) {
                continue;
            }

            final Flow flow;
            if (isLatestCommit) {
                // If this is the latest commit, then create one.
                flow = bucket.getFlowOrCreate(flowId);
            } else {
                // Otherwise non-existing flow means it's already deleted.
                final Optional<Flow> flowOpt = bucket.getFlow(flowId);
                if (flowOpt.isPresent()) {
                    flow = flowOpt.get();
                } else {
                    logger.debug("Flow {} does not exist in bucket {}:{} any longer. It may have been deleted.", flowId, bucket.getBucketDirName(), bucket.getBucketId());
                    continue;
                }
            }

            final int version = (int) flowMeta.get(VER);
            final String flowSnapshotFilename = (String) flowMeta.get(FILE);

            // Since commits are read in LIFO order, avoid old commits overriding the latest pointer.
            if (!flow.hasVersion(version)) {
                final Flow.FlowPointer pointer = new Flow.FlowPointer(flowSnapshotFilename);
                final File flowSnapshotFile = new File(new File(backetFilePath).getParent(), flowSnapshotFilename);
                final ObjectId objectId = flowSnapshotObjectIds.get(flowSnapshotFile.getPath().replaceAll("\\\\", "/"));
                if (objectId == null) {
                    logger.warn("Git object id for Flow {} version {} with path {} in bucket {}:{} was not found. Ignoring this entry.",
                            flowId, version, flowSnapshotFile.getPath(), bucket.getBucketDirName(), bucket.getBucketId());
                    continue;
                }
                pointer.setGitRev(commit.getName());
                pointer.setObjectId(objectId.getName());

                if (flowMeta.containsKey(FLOW_NAME)) {
                    pointer.setFlowName((String)flowMeta.get(FLOW_NAME));
                }
                if (flowMeta.containsKey(FLOW_DESC)) {
                    pointer.setFlowDescription((String)flowMeta.get(FLOW_DESC));
                }
                if (flowMeta.containsKey(AUTHOR)) {
                    pointer.setAuthor((String)flowMeta.get(AUTHOR));
                }else{
                    pointer.setAuthor(commit.getCommitterIdent().getName());
                }
                if (flowMeta.containsKey(COMMENTS)) {
                    pointer.setComment((String)flowMeta.get(COMMENTS));
                }else{
                    pointer.setComment(commit.getFullMessage());
                }
                if (flowMeta.containsKey(CREATED)) {
                    pointer.setCreated((long)flowMeta.get(CREATED));
                }else{
                    pointer.setCreated(commit.getCommitTime() * 1000L);
                }

                flow.putVersion(version, pointer);
            }
        }
    }

    private boolean validateRequiredValue(final Map map, String nameOfMap, Object... keys) {
        for (Object key : keys) {
            if (!map.containsKey(key)) {
                logger.warn("{} does not have {}. Skipping it.", nameOfMap, key);
                return false;
            }
        }
        return true;
    }

    public Bucket getBucketOrCreate(String bucketId) {
        return buckets.computeIfAbsent(bucketId, k -> new Bucket(bucketId));
    }

    public Optional<Bucket> getBucket(String bucketId) {
        return Optional.ofNullable(buckets.get(bucketId));
    }

    Map<String, Bucket> getBuckets() {
        return buckets;
    }

    void saveBucket(final Bucket bucket, final File bucketDir) throws IOException {
        final Yaml yaml = new Yaml();
        final Map<String, Object> serializedBucket = bucket.serialize();
        final File bucketFile = new File(bucketDir, GitFlowMetaData.BUCKET_FILENAME);

        try (final Writer writer = new OutputStreamWriter(
                new FileOutputStream(bucketFile), StandardCharsets.UTF_8)) {
            yaml.dump(serializedBucket, writer);
        }
    }

    boolean isGitDirectoryClean() throws GitAPIException {
        final Status status = new Git(gitRepo).status().call();
        return status.isClean() && !status.hasUncommittedChanges();
    }

    boolean isGitRepositoryExisting(File gitRepository) throws IOException {
        return this.createRepositoryBuilder(gitRepository).getGitDir() != null;
    }

    /**
     * Create a Git commit.
     *
     * @param author      The name of a NiFi Registry user who created the snapshot. It will be added to the commit message.
     * @param message     Commit message.
     * @param bucket      A bucket to commit.
     * @param flowPointer A flow pointer for the flow snapshot which is updated.
     *                    After a commit is created, new commit rev id and flow snapshot file object id are set to this pointer.
     *                    It can be null if none of flow content is modified.
     */
    void commit(String author, String message, Bucket bucket, Flow.FlowPointer flowPointer) throws GitAPIException, IOException {
        try (final Git git = new Git(gitRepo)) {
            // Execute add command for newly added files (if any).
            git.add().addFilepattern(".").call();

            // Execute add command again for deleted files (if any).
            git.add().addFilepattern(".").setUpdate(true).call();

            final String commitMessage = isEmpty(author) ? message
                    : format("%s\n\nBy NiFi Registry user: %s", message, author);
            final RevCommit commit = git.commit()
                    .setMessage(commitMessage)
                    .call();

            if (flowPointer != null) {
                final RevTree tree = commit.getTree();
                final String bucketDirName = bucket.getBucketDirName();
                final String flowSnapshotPath = new File(bucketDirName, flowPointer.getFileName()).getPath();
                try (final TreeWalk treeWalk = new TreeWalk(gitRepo)) {
                    treeWalk.addTree(tree);

                    while (treeWalk.next()) {
                        if (treeWalk.isSubtree()) {
                            treeWalk.enterSubtree();
                        } else {
                            final String pathString = treeWalk.getPathString();
                            if (pathString.equals(flowSnapshotPath)) {
                                // Capture updated object id.
                                final String flowSnapshotObjectId = treeWalk.getObjectId(0).getName();
                                flowPointer.setObjectId(flowSnapshotObjectId);
                                break;
                            }
                        }
                    }
                }

                flowPointer.setGitRev(commit.getName());
            }

            // Push if necessary.
            if (!isEmpty(remoteToPush)) {
                // Use different thread since it takes longer.
                final long offeredTimestamp = System.currentTimeMillis();
                if (pushQueue.offer(offeredTimestamp)) {
                    logger.debug("New push request is offered at {}.", offeredTimestamp);
                }
            }

        }
    }

    byte[] getContent(String objectId) throws IOException {
        final ObjectId flowSnapshotObjectId = gitRepo.resolve(objectId);
        return gitRepo.newObjectReader().open(flowSnapshotObjectId).getBytes();
    }

    public SyncStatus getStatus() throws GitAPIException {
        final Status status = new Git(this.gitRepo).status().call();
        return new SyncStatus(
                status.isClean(),
                status.hasUncommittedChanges(),
                status.getConflictingStageState().keySet()) ;
    }
}
