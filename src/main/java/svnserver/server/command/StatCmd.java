package svnserver.server.command;

import org.jetbrains.annotations.NotNull;
import org.tmatesoft.svn.core.SVNException;
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
 * stat
 *    params:   ( path:string [ rev:number ] )
 *    response: ( ? entry:dirent )
 *    dirent:   ( name:string kind:node-kind size:number has-props:bool
 *    created-rev:number [ created-date:string ]
 *    [ last-author:string ] )
 *    New in svn 1.2.  If path is non-existent, an empty response is returned.
 * </pre>
 *
 * @author a.navrotskiy
 */
public class StatCmd extends BaseCmd<StatCmd.Params> {
  public static class Params {
    @NotNull
    private final String path;
    @NotNull
    private final int[] rev;

    public Params(@NotNull String path, @NotNull int[] rev) {
      this.path = path;
      this.rev = rev;
    }
  }

  @NotNull
  @Override
  public Class<Params> getArguments() {
    return Params.class;
  }

  @Override
  protected void processCommand(@NotNull SessionContext context, @NotNull Params args) throws IOException, SVNException {
    final SvnServerWriter writer = context.getWriter();
    final String fullPath = context.getRepositoryPath(args.path);
    final VcsRepository repository = context.getRepository();
    final VcsRevision info = repository.getRevisionInfo(getRevision(args.rev, repository.getLatestRevision().getId()));
    final VcsFile fileInfo = info.getFile(fullPath);
    if (fileInfo == null) {
      sendError(writer, 200009, "File not found");
      return;
    }
    writer
        .listBegin()
        .word("success")
        .listBegin()
        .listBegin()
        .listBegin()
        .word(fileInfo.getKind().toString()) // kind
        .number(fileInfo.getSize()) // size
        .bool(!fileInfo.getProperties(false).isEmpty()) // has properties
        .number(fileInfo.getLastChange().getId()) // last change revision
        .listBegin().stringNullable(fileInfo.getLastChange().getDateString()).listEnd() // last change date
        .listBegin().stringNullable(fileInfo.getLastChange().getAuthor()).listEnd() // last change author
        .listEnd()
        .listEnd()
        .listEnd()
        .listEnd();
  }
}
