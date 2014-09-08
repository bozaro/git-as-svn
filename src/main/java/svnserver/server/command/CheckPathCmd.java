package svnserver.server.command;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNNodeKind;
import svnserver.parser.SvnServerWriter;
import svnserver.repository.VcsFile;
import svnserver.repository.VcsRepository;
import svnserver.repository.VcsRevision;
import svnserver.server.SessionContext;

import java.io.IOException;

/**
 * Change current path in repository.
 * <p>
 * <pre>
 * check-path
 *    params:   ( path:string [ rev:number ] )
 *    response: ( kind:node-kind )
 *    If path is non-existent, 'svn_node_none' kind is returned.
 * </pre>
 *
 * @author a.navrotskiy
 */
public class CheckPathCmd extends BaseCmd<CheckPathCmd.Params> {
  public static class Params {
    @NotNull
    private final String path;
    @NotNull
    private final int[] rev;

    public Params(@NotNull String path, @NotNull int[] rev) {
      this.path = path;
      this.rev = rev;
    }

    @Nullable
    public Integer getRev() {
      return rev.length < 1 ? null : rev[0];
    }
  }

  @NotNull
  @Override
  public Class<Params> getArguments() {
    return Params.class;
  }

  @Override
  protected void processCommand(@NotNull SessionContext context, @NotNull Params args) throws IOException, SVNException {
    String fullPath = context.getRepositoryPath(args.path);
    final VcsRepository repository = context.getRepository();
    final VcsRevision info = repository.getRevisionInfo(getRevision(args.rev, () -> repository.getLatestRevision().getId()));
    VcsFile fileInfo = info.getFile(fullPath);
    final SVNNodeKind kind;
    if (fileInfo != null) {
      kind = fileInfo.getKind();
    } else {
      kind = SVNNodeKind.NONE;
    }
    final SvnServerWriter writer = context.getWriter();
    writer
        .listBegin()
        .word("success")
        .listBegin()
        .word(kind.toString()) // kind
        .listEnd()
        .listEnd();
  }
}
