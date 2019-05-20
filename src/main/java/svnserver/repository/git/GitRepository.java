/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.repository.git;

import com.sun.nio.sctp.InvalidStreamException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mapdb.DB;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.internal.wc.SVNFileUtil;
import svnserver.StringHelper;
import svnserver.context.LocalContext;
import svnserver.context.SharedContext;
import svnserver.repository.SvnForbiddenException;
import svnserver.repository.VcsSupplier;
import svnserver.repository.git.filter.GitFilter;
import svnserver.repository.git.filter.GitFilterHelper;
import svnserver.repository.git.filter.GitFilterLink;
import svnserver.repository.git.filter.GitFilterRaw;
import svnserver.repository.git.prop.GitProperty;
import svnserver.repository.git.prop.GitPropertyFactory;
import svnserver.repository.git.prop.PropertyMapping;
import svnserver.repository.git.push.GitPusher;
import svnserver.repository.locks.LockDesc;
import svnserver.repository.locks.LockDescSerializer;
import svnserver.repository.locks.LockManager;
import svnserver.repository.locks.LockWorker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Implementation for Git repository.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public final class GitRepository implements AutoCloseable, BranchProvider {
  @NotNull
  public static final byte[] emptyBytes = {};

  private static final int lockDescCacheVersion = 3;
  @NotNull
  private final Repository git;
  @NotNull
  private final GitPusher pusher;
  @NotNull
  private final LocalContext context;
  @NotNull
  private final HTreeMap<String, Boolean> binaryCache;
  @NotNull
  private final Map<String, GitFilter> gitFilters;
  @NotNull
  private final Map<ObjectId, GitProperty[]> directoryPropertyCache = new ConcurrentHashMap<>();
  @NotNull
  private final Map<ObjectId, GitProperty[]> filePropertyCache = new ConcurrentHashMap<>();
  private final boolean renameDetection;
  @NotNull
  private final ReadWriteLock lockManagerRwLock = new ReentrantReadWriteLock();
  @NotNull
  private final LockManager lockManager;
  @NotNull
  private final DB db;
  @NotNull
  private final NavigableMap<String, GitBranch> branches = new TreeMap<>();

  public GitRepository(@NotNull LocalContext context,
                       @NotNull Repository git,
                       @NotNull GitPusher pusher,
                       @NotNull Set<String> branches,
                       boolean renameDetection) throws IOException {
    this.context = context;
    final SharedContext shared = context.getShared();
    shared.getOrCreate(GitSubmodules.class, GitSubmodules::new).register(git);
    this.git = git;
    db = shared.getCacheDB();
    this.binaryCache = db.hashMap("cache.binary", Serializer.STRING, Serializer.BOOLEAN).createOrOpen();

    this.pusher = pusher;
    this.renameDetection = renameDetection;

    final String lockCacheName = String.format("locks.%s.%s", context.getName(), lockDescCacheVersion);
    final SortedMap<String, LockDesc> lockMap = db.treeMap(
        lockCacheName, Serializer.STRING, LockDescSerializer.instance
    ).createOrOpen();
    lockManager = new LockManager(lockMap);

    this.gitFilters = GitFilterHelper.createFilters(context);
    for (String branch : branches)
      this.branches.put(StringHelper.normalizeDir(branch), new GitBranch(this, branch));
  }

  @NotNull
  public NavigableMap<String, GitBranch> getBranches() {
    return branches;
  }

  boolean hasRenameDetection() {
    return renameDetection;
  }

  @NotNull
  public LocalContext getContext() {
    return context;
  }

  public void close() {
    context.getShared().sure(GitSubmodules.class).unregister(git);
  }

  @NotNull
  public <T> T wrapLockWrite(@NotNull LockWorker<T> work) throws SVNException, IOException {
    final T result = wrapLock(lockManagerRwLock.writeLock(), work);
    db.commit();
    return result;
  }

  @NotNull
  private <T> T wrapLock(@NotNull Lock lock, @NotNull LockWorker<T> work) throws IOException, SVNException {
    lock.lock();
    try {
      return work.exec(lockManager);
    } finally {
      lock.unlock();
    }
  }

  @NotNull
  GitProperty[] collectProperties(@NotNull GitTreeEntry treeEntry, @NotNull VcsSupplier<Iterable<GitTreeEntry>> entryProvider) throws IOException, SVNException {
    if (treeEntry.getFileMode().getObjectType() == Constants.OBJ_BLOB)
      return GitProperty.emptyArray;

    GitProperty[] props = directoryPropertyCache.get(treeEntry.getObjectId().getObject());
    if (props == null) {
      final List<GitProperty> propList = new ArrayList<>();
      try {
        for (GitTreeEntry entry : entryProvider.get()) {
          final GitProperty[] parseProps = parseGitProperty(entry.getFileName(), entry.getObjectId());
          if (parseProps.length > 0) {
            propList.addAll(Arrays.asList(parseProps));
          }
        }
      } catch (SvnForbiddenException ignored) {
      }
      props = propList.toArray(GitProperty.emptyArray);
      directoryPropertyCache.put(treeEntry.getObjectId().getObject(), props);
    }
    return props;
  }

  @NotNull
  private GitProperty[] parseGitProperty(@NotNull String fileName, @NotNull GitObject<ObjectId> objectId) throws IOException {
    final GitPropertyFactory factory = PropertyMapping.getFactory(fileName);
    if (factory == null)
      return GitProperty.emptyArray;

    return cachedParseGitProperty(objectId, factory);
  }

  @NotNull
  private GitProperty[] cachedParseGitProperty(GitObject<ObjectId> objectId, GitPropertyFactory factory) throws IOException {
    GitProperty[] property = filePropertyCache.get(objectId.getObject());
    if (property == null) {
      property = factory.create(loadContent(objectId.getRepo().newObjectReader(), objectId.getObject()));
      if (property.length == 0) {
        property = GitProperty.emptyArray;
      }
      filePropertyCache.put(objectId.getObject(), property);
    }
    return property;
  }

  @NotNull
  static String loadContent(@NotNull ObjectReader reader, @NotNull ObjectId objectId) throws IOException {
    final byte[] bytes = reader.open(objectId).getCachedBytes();
    return new String(bytes, StandardCharsets.UTF_8);
  }

  @NotNull
  GitFilter getFilter(@NotNull FileMode fileMode, @NotNull GitProperty[] props) {
    if (fileMode.getObjectType() != Constants.OBJ_BLOB) {
      return gitFilters.get(GitFilterRaw.NAME);
    }
    if (fileMode == FileMode.SYMLINK) {
      return gitFilters.get(GitFilterLink.NAME);
    }
    for (int i = props.length - 1; i >= 0; --i) {
      final String filterName = props[i].getFilterName();
      if (filterName != null) {
        final GitFilter filter = gitFilters.get(filterName);
        if (filter == null) {
          throw new InvalidStreamException("Unknown filter requested: " + filterName);
        }
        return filter;
      }
    }
    return gitFilters.get(GitFilterRaw.NAME);
  }

  @NotNull
  public Repository getGit() {
    return git;
  }

  boolean isObjectBinary(@Nullable GitFilter filter, @Nullable GitObject<? extends ObjectId> objectId) throws IOException, SVNException {
    if (objectId == null || filter == null) return false;
    final String key = filter.getName() + " " + objectId.getObject().name();
    Boolean result = binaryCache.get(key);
    if (result == null) {
      try (InputStream stream = filter.inputStream(objectId)) {
        result = SVNFileUtil.detectMimeType(stream) != null;
      }
      binaryCache.putIfAbsent(key, result);
    }
    return result;
  }

  @NotNull
  Iterable<GitTreeEntry> loadTree(@Nullable GitTreeEntry tree) throws IOException {
    final GitObject<ObjectId> treeId = getTreeObject(tree);
    // Loading tree.
    if (treeId == null) {
      return Collections.emptyList();
    }
    final List<GitTreeEntry> result = new ArrayList<>();
    final Repository repo = treeId.getRepo();
    final CanonicalTreeParser treeParser = new CanonicalTreeParser(GitRepository.emptyBytes, repo.newObjectReader(), treeId.getObject());
    while (!treeParser.eof()) {
      result.add(new GitTreeEntry(
          treeParser.getEntryFileMode(),
          new GitObject<>(repo, treeParser.getEntryObjectId()),
          treeParser.getEntryPathString()
      ));
      treeParser.next();
    }
    return result;
  }

  @Nullable
  private GitObject<ObjectId> getTreeObject(@Nullable GitTreeEntry tree) throws IOException {
    if (tree == null) {
      return null;
    }
    // Get tree object
    if (tree.getFileMode().equals(FileMode.TREE)) {
      return tree.getObjectId();
    }
    if (tree.getFileMode().equals(FileMode.GITLINK)) {
      GitObject<RevCommit> linkedCommit = loadLinkedCommit(tree.getObjectId().getObject());
      if (linkedCommit == null) {
        throw new SvnForbiddenException();
      }
      return new GitObject<>(linkedCommit.getRepo(), linkedCommit.getObject().getTree());
    } else {
      return null;
    }
  }

  @Nullable
  private GitObject<RevCommit> loadLinkedCommit(@NotNull ObjectId objectId) throws IOException {
    return context.getShared().sure(GitSubmodules.class).findCommit(objectId);
  }

  @NotNull
  public <T> T wrapLockRead(@NotNull LockWorker<T> work) throws SVNException, IOException {
    return wrapLock(lockManagerRwLock.readLock(), work);
  }

  @NotNull GitPusher getPusher() {
    return pusher;
  }
}
