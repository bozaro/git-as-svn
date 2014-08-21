package svnserver.repository.git;

import org.eclipse.jgit.internal.storage.file.FileRepository;
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
import org.tmatesoft.svn.core.io.diff.SVNDeltaProcessor;
import org.tmatesoft.svn.core.io.diff.SVNDiffWindow;
import svnserver.repository.VcsDeltaConsumer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Delta consumer for applying svn diff on git blob.
 *
 * @author a.navrotskiy
 */
public class GitDeltaConsumer implements VcsDeltaConsumer {
  @NotNull
  private static final Logger log = LoggerFactory.getLogger(GitDeltaConsumer.class);
  @NotNull
  private final Map<String, String> props;
  @NotNull
  private GitRepository gitRepository;
  @Nullable
  private SVNDeltaProcessor window;
  @Nullable
  private final GitObject<ObjectId> originalId;
  @Nullable
  private final String originalMd5;
  @NotNull
  private final FileMode fileMode;
  @NotNull
  private final String path;

  @Nullable
  private GitObject<ObjectId> objectId;

  // todo: Wrap output stream for saving big blob to temporary files.
  @NotNull
  private ByteArrayOutputStream memory;
  private final boolean update;

  public GitDeltaConsumer(@NotNull GitRepository gitRepository, @NotNull String path, @Nullable GitFile file, boolean update) throws IOException {
    this.gitRepository = gitRepository;
    if (file != null) {
      this.fileMode = file.getFileMode();
      this.originalMd5 = file.getMd5();
      this.originalId = file.getObjectId();
      this.props = new HashMap<>(file.getProperties(false));
    } else {
      this.fileMode = FileMode.REGULAR_FILE;
      this.originalMd5 = null;
      this.originalId = null;
      this.props = new HashMap<>();
    }
    this.path = path;
    this.update = update;
    this.objectId = originalId;
    this.memory = new ByteArrayOutputStream();
  }

  @NotNull
  @Override
  public String getPath() {
    return path;
  }

  @NotNull
  @Override
  public Map<String, String> getProperties() {
    return props;
  }

  @Nullable
  public GitObject<ObjectId> getOriginalId() {
    return originalId;
  }

  @Nullable
  public GitObject<ObjectId> getObjectId() {
    return objectId;
  }

  @Override
  public void applyTextDelta(String path, @Nullable String baseChecksum) throws SVNException {
    try {
      if ((originalMd5 != null) && (baseChecksum != null)) {
        if (!baseChecksum.equals(originalMd5)) {
          throw new SVNException(SVNErrorMessage.create(SVNErrorCode.CHECKSUM_MISMATCH));
        }
      }
      if (window != null) {
        throw new SVNException(SVNErrorMessage.UNKNOWN_ERROR_MESSAGE);
      }
      window = new SVNDeltaProcessor();
      window.applyTextDelta(objectId != null ? objectId.openObject().openStream() : new ByteArrayInputStream(GitRepository.emptyBytes), memory, true);
    } catch (IOException e) {
      throw new SVNException(SVNErrorMessage.create(SVNErrorCode.IO_ERROR), e);
    }
  }

  @Override
  public OutputStream textDeltaChunk(String path, SVNDiffWindow diffWindow) throws SVNException {
    if (window == null) {
      throw new SVNException(SVNErrorMessage.UNKNOWN_ERROR_MESSAGE);
    }
    return window.textDeltaChunk(diffWindow);
  }

  @Override
  public void textDeltaEnd(String path) throws SVNException {
    try {
      if (window == null) {
        throw new SVNException(SVNErrorMessage.UNKNOWN_ERROR_MESSAGE);
      }
      final Repository repo = gitRepository.getRepository();
      objectId = new GitObject<>(repo, repo.newObjectInserter().insert(Constants.OBJ_BLOB, memory.toByteArray()));
      log.info("Created blob {} for file: {}", objectId.getObject().getName(), path);
    } catch (IOException e) {
      throw new SVNException(SVNErrorMessage.create(SVNErrorCode.IO_ERROR), e);
    }
  }

  @Override
  public void validateChecksum(@NotNull String md5) throws SVNException {
    if (window != null) {
      if (!md5.equals(window.textDeltaEnd())) {
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.CHECKSUM_MISMATCH));
      }
    } else if (originalMd5 != null) {
      if (!originalMd5.equals(md5)) {
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.CHECKSUM_MISMATCH));
      }
    }
  }

  @NotNull
  public FileMode getFileMode() {
    return fileMode;
  }

  public boolean isUpdate() {
    return update;
  }
}
