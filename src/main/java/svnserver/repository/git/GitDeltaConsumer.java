/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.repository.git;

import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNProperty;
import org.tmatesoft.svn.core.io.ISVNDeltaConsumer;
import org.tmatesoft.svn.core.io.diff.SVNDeltaProcessor;
import org.tmatesoft.svn.core.io.diff.SVNDiffWindow;
import svnserver.TemporaryOutputStream;
import svnserver.auth.User;
import svnserver.repository.git.filter.GitFilter;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Delta consumer for applying svn diff on git blob.
 *
 * @author a.navrotskiy
 */
public final class GitDeltaConsumer implements ISVNDeltaConsumer {
  @NotNull
  private static final Logger log = LoggerFactory.getLogger(GitDeltaConsumer.class);
  @NotNull
  private final Map<String, String> props;
  @NotNull
  private final GitWriter writer;
  @NotNull
  private final GitEntry entry;
  @NotNull
  private final User user;
  @Nullable
  private final GitObject<ObjectId> originalId;
  @Nullable
  private final String originalMd5;
  @Nullable
  private final GitFilter oldFilter;
  @Nullable
  private SVNDeltaProcessor window;
  @Nullable
  private GitObject<ObjectId> objectId;
  @Nullable
  private GitFilter newFilter;

  @NotNull
  private TemporaryOutputStream temporaryStream;
  @Nullable
  private String md5;

  GitDeltaConsumer(@NotNull GitWriter writer, @NotNull GitEntry entry, @Nullable GitFile file, @NotNull User user) throws IOException, SVNException {
    this.writer = writer;
    this.entry = entry;
    this.user = user;
    if (file != null) {
      this.originalMd5 = file.getMd5();
      this.originalId = file.getObjectId();
      this.props = new HashMap<>(file.getProperties());
      this.oldFilter = file.getFilter();
    } else {
      this.originalMd5 = null;
      this.originalId = null;
      this.props = new HashMap<>();
      this.oldFilter = null;
    }
    this.newFilter = null;
    this.objectId = originalId;
    this.temporaryStream = new TemporaryOutputStream();
  }

  @NotNull
  public Map<String, String> getProperties() {
    return props;
  }

  public void validateChecksum(@NotNull String md5) throws SVNException {
    if (window != null) {
      if (!md5.equals(this.md5)) {
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.CHECKSUM_MISMATCH));
      }
    } else if (originalMd5 != null) {
      if (!originalMd5.equals(md5)) {
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.CHECKSUM_MISMATCH));
      }
    }
  }

  @Nullable GitObject<ObjectId> getOriginalId() {
    return originalId;
  }

  @Nullable
  public GitObject<ObjectId> getObjectId() throws IOException, SVNException {
    if ((originalId != null) && originalId.equals(objectId) && (newFilter == null)) {
      this.newFilter = oldFilter;
      this.objectId = originalId;
      if (oldFilter == null) {
        throw new IllegalStateException("Original object ID defined, but original Filter is not defined");
      }
      migrateFilter(writer.getBranch().getRepository().getFilter(props.containsKey(SVNProperty.SPECIAL) ? FileMode.SYMLINK : FileMode.REGULAR_FILE, entry.getRawProperties()));
    }
    return objectId;
  }

  boolean migrateFilter(@NotNull GitFilter filter) throws IOException, SVNException {
    if (newFilter == null || objectId == null) {
      throw new IllegalStateException("Original object ID defined, but original Filter is not defined");
    }
    final GitObject<ObjectId> beforeId = objectId;
    if (!newFilter.equals(filter)) {
      final Repository repo = writer.getBranch().getRepository().getGit();

      try (
          final TemporaryOutputStream content = new TemporaryOutputStream();
          final TemporaryOutputStream.Holder holder = content.holder()
      ) {
        try (InputStream inputStream = newFilter.inputStream(objectId);
             OutputStream outputStream = filter.outputStream(content, user)) {
          IOUtils.copy(inputStream, outputStream);
        }
        try (InputStream inputStream = content.toInputStream()) {
          objectId = new GitObject<>(repo, writer.getInserter().insert(Constants.OBJ_BLOB, content.size(), inputStream));
          newFilter = filter;
        }
      }
    }
    return !beforeId.equals(objectId);
  }

  public void applyTextDelta(String path, @Nullable String baseChecksum) throws SVNException {
    try {
      if ((originalMd5 != null) && (baseChecksum != null)) {
        if (!baseChecksum.equals(originalMd5)) {
          throw new SVNException(SVNErrorMessage.create(SVNErrorCode.CHECKSUM_MISMATCH));
        }
      }

      if (window != null)
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.RA_SVN_CMD_ERR));

      newFilter = writer.getBranch().getRepository().getFilter(props.containsKey(SVNProperty.SPECIAL) ? FileMode.SYMLINK : FileMode.REGULAR_FILE, entry.getRawProperties());
      window = new SVNDeltaProcessor();

      final InputStream base = (oldFilter != null && objectId != null) ? oldFilter.inputStream(objectId) : new ByteArrayInputStream(GitRepository.emptyBytes);
      final OutputStream target = newFilter.outputStream(temporaryStream, user);

      window.applyTextDelta(base, new UncheckedCloseOutputStream(target), true);
    } catch (IOException e) {
      throw new SVNException(SVNErrorMessage.create(SVNErrorCode.IO_ERROR), e);
    }
  }

  public OutputStream textDeltaChunk(String path, SVNDiffWindow diffWindow) throws SVNException {
    if (window == null)
      throw new SVNException(SVNErrorMessage.create(SVNErrorCode.RA_SVN_CMD_ERR));

    return window.textDeltaChunk(diffWindow);
  }

  public void textDeltaEnd(String path) throws SVNException {
    try (TemporaryOutputStream.Holder holder = temporaryStream.holder()) {
      if (window == null)
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.RA_SVN_CMD_ERR));

      final Repository repo = writer.getBranch().getRepository().getGit();
      md5 = window.textDeltaEnd();
      try (InputStream stream = temporaryStream.toInputStream()) {
        objectId = new GitObject<>(repo, writer.getInserter().insert(Constants.OBJ_BLOB, temporaryStream.size(), stream));
      }
      log.info("Created blob {} for file: {}", objectId.getObject().getName(), entry.getFullPath());
    } catch (IOException e) {
      throw new SVNException(SVNErrorMessage.create(SVNErrorCode.IO_ERROR), e);
    } catch (RuntimeIOException e) {
      throw new SVNException(SVNErrorMessage.create(SVNErrorCode.IO_ERROR), e.getCause());
    }
  }

  @NotNull
  public String getFilterName() {
    if (newFilter != null)
      return newFilter.getName();
    if (oldFilter != null)
      return oldFilter.getName();
    throw new IllegalStateException();
  }

  /**
   * Dirty hack to workaround silent ignoring of IOException in {@link SVNDeltaProcessor#textDeltaEnd()}
   */
  private static final class UncheckedCloseOutputStream extends FilterOutputStream {
    private UncheckedCloseOutputStream(@NotNull OutputStream out) {
      super(out);
    }

    @Override
    public void close() {
      try {
        super.close();
      } catch (IOException e) {
        throw new RuntimeIOException(e);
      }
    }
  }
}
