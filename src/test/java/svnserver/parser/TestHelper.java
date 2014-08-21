package svnserver.parser;

import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.*;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Common test functions.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public class TestHelper {
  public static void saveFile(@NotNull File file, @NotNull String content) throws IOException {
    try (OutputStream stream = new FileOutputStream(file)) {
      stream.write(content.getBytes(StandardCharsets.UTF_8));
    }
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  public static File createTempDir(@NotNull String prefix) throws IOException {
    final File dir = File.createTempFile(prefix, "");
    dir.delete();
    dir.mkdir();
    return dir;
  }

  public static Repository emptyRepository(@NotNull String branch) throws IOException {
    // todo: final Repository repository = new InMemoryRepository(new DfsRepositoryDescription(null));
    final Repository repository = new FileRepository(createTempDir("git-empty"));
    repository.create();
    // Create empty commit.
    final ObjectInserter inserter = repository.newObjectInserter();
    final TreeFormatter treeBuilder = new TreeFormatter();
    final ObjectId treeId = inserter.insert(treeBuilder);

    final CommitBuilder commitBuilder = new CommitBuilder();
    commitBuilder.setAuthor(new PersonIdent("", "", 0, 0));
    commitBuilder.setCommitter(new PersonIdent("", "", 0, 0));
    commitBuilder.setMessage("Empty commit");
    commitBuilder.setTreeId(treeId);
    final ObjectId commitId = inserter.insert(commitBuilder);
    inserter.flush();

    // Create branch
    final RefUpdate updateRef = repository.updateRef(Constants.R_HEADS + branch);
    updateRef.setNewObjectId(commitId);
    updateRef.update();

    return repository;
  }
}
