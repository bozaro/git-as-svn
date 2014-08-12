package svnserver.repository.git;

import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import svnserver.StringHelper;
import svnserver.SvnConstants;
import svnserver.repository.FileInfo;
import svnserver.repository.Repository;
import svnserver.repository.RevisionInfo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Implementation for Git repository.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public class GitRepository implements Repository {
  @NotNull
  private final FileRepository repository;
  @NotNull
  private final List<RevCommit> revisions;
  @NotNull
  private final Map<String, String> cacheMd5 = new ConcurrentHashMap<>();

  public GitRepository() throws IOException {
    repository = new FileRepository(findGitPath());
    revisions = loadRevisions(repository);
  }

  private static List<RevCommit> loadRevisions(@NotNull FileRepository repository) throws IOException {
    final Ref master = repository.getRef("master");
    final LinkedList<RevCommit> revisions = new LinkedList<>();
    final RevWalk revWalk = new RevWalk(repository);
    ObjectId objectId = master.getObjectId();
    while (true) {
      final RevCommit commit = revWalk.parseCommit(objectId);
      revisions.addFirst(commit);
      if (commit.getParentCount() == 0) break;
      objectId = commit.getParent(0);
    }
    return new ArrayList<>(revisions);
  }

  private File findGitPath() {
    final File root = new File(".").getAbsoluteFile();
    File path = root;
    while (true) {
      final File repo = new File(path, ".git");
      if (repo.exists()) {
        return repo;
      }
      path = path.getParentFile();
      if (path == null) {
        throw new IllegalStateException("Repository not found from directiry: " + root.getAbsolutePath());
      }
    }
  }

  @Override
  public int getLatestRevision() throws IOException {
    return revisions.size();
  }

  @NotNull
  private String getObjectMD5(@NotNull ObjectId objectId) throws IOException {
    //repository.newObjectReader().open(
    String result = cacheMd5.get(objectId.name());
    if (result == null) {
      final byte[] buffer = new byte[64 * 1024];
      final MessageDigest md5 = getMd5();
      try (InputStream stream = openObject(objectId).openStream()) {
        while (true) {
          int size = stream.read(buffer);
          if (size < 0) break;
          md5.update(buffer, 0, size);
        }
      }
      result = StringHelper.toHex(md5.digest());
      cacheMd5.putIfAbsent(objectId.name(), result);
    }
    return result;
  }

  @NotNull
  private ObjectLoader openObject(@NotNull ObjectId objectId) throws IOException {
    return repository.newObjectReader().open(objectId);
  }

  @NotNull
  @Override
  public RevisionInfo getRevisionInfo(int revision) throws IOException {
    final RevCommit commit = getRevision(revision);
    Map<String, String> props = new HashMap<>();
    props.put(SvnConstants.PROP_AUTHOR, commit.getCommitterIdent().getName());
    props.put(SvnConstants.PROP_LOG, commit.getFullMessage().trim());
    props.put(SvnConstants.PROP_DATE, StringHelper.formatDate(TimeUnit.SECONDS.toMillis(commit.getCommitTime())));
    props.put(SvnConstants.PROP_GIT, commit.name());
    return new GitRevisionInfo(revision, props, commit);
  }

  private static MessageDigest getMd5() {
    try {
      return MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @NotNull
  private RevCommit getRevision(int revision) {
    return revisions.get(revision - 1);
  }

  private class GitRevisionInfo implements RevisionInfo {
    private final int revision;
    private final Map<String, String> props;
    private final RevCommit commit;

    public GitRevisionInfo(int revision, Map<String, String> props, RevCommit commit) {
      this.revision = revision;
      this.props = props;
      this.commit = commit;
    }

    @Override
    public int getId() {
      return revision;
    }

    @NotNull
    @Override
    public Map<String, String> getProperties() {
      return props;
    }

    @Nullable
    @Override
    public FileInfo getFile(@NotNull String fullPath) throws IOException {
      final TreeWalk treeWalk = TreeWalk.forPath(repository, fullPath.substring(1), commit.getTree());
      if (treeWalk == null) {
        return null;
      }
      if (treeWalk.getFileMode(0).equals(FileMode.TREE)) {
        return null;
      }
      //return treeWalk;
      return new GitFileInfo(treeWalk);
    }
  }

  private class GitFileInfo implements FileInfo {
    private final TreeWalk treeWalk;
    @Nullable
    private ObjectLoader objectLoader;

    public GitFileInfo(TreeWalk treeWalk) {
      this.treeWalk = treeWalk;
    }

    @NotNull
    @Override
    public Map<String, String> getProperties() {
      final Map<String, String> props = new HashMap<>();
      if (treeWalk.getFileMode(0).equals(FileMode.EXECUTABLE_FILE)) {
        props.put(SvnConstants.PROP_EXEC, "*");
      }
      return props;
    }

    @NotNull
    @Override
    public String getMd5() throws IOException {
      return getObjectMD5(treeWalk.getObjectId(0));
    }

    @Override
    public long getSize() throws IOException {
      return getObjectLoader().getSize();
    }

    private ObjectLoader getObjectLoader() throws IOException {
      if (objectLoader == null) {
        objectLoader = openObject(treeWalk.getObjectId(0));
      }
      return objectLoader;
    }

    @Override
    public void copyTo(@NotNull OutputStream stream) throws IOException {
      getObjectLoader().copyTo(stream);
    }
  }
}