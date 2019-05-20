/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.server.command;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tmatesoft.svn.core.SVNException;
import svnserver.parser.SvnServerWriter;
import svnserver.repository.locks.LockDesc;
import svnserver.repository.locks.LockTarget;
import svnserver.server.SessionContext;

import java.io.IOException;

/**
 * <pre>
 * lock
 *    params:    ( path:string [ comment:string ] steal-lock:bool
 *                 [ current-rev:number ] )
 *    response:  ( lock:lockdesc )
 * </pre>
 *
 * @author Marat Radchenko <marat@slonopotamus.org>
 */
public final class LockCmd extends BaseCmd<LockCmd.Params> {

  @NotNull
  @Override
  public Class<? extends Params> getArguments() {
    return Params.class;
  }

  @Override
  protected void processCommand(@NotNull SessionContext context, @NotNull Params args) throws IOException, SVNException {
    final int rev = getRevisionOrLatest(args.rev, context);
    final String path = context.getRepositoryPath(args.path);
    final LockTarget lockTarget = new LockTarget(path, rev);
    final String comment = args.comment.length == 0 ? null : args.comment[0];
    final LockDesc[] lockDescs = context.getBranch().getRepository().wrapLockWrite((lockManager) -> lockManager.lock(context.getUser(), context.getBranch(), comment, args.stealLock, new LockTarget[]{lockTarget}));
    if (lockDescs.length != 1) {
      throw new IllegalStateException();
    }
    final SvnServerWriter writer = context.getWriter();
    writer.listBegin()
        .word("success")
        .listBegin();
    LockCmd.writeLock(writer, lockDescs[0]);
    writer
        .listEnd()
        .listEnd();
  }

  @Override
  protected void permissionCheck(@NotNull SessionContext context, @NotNull Params args) throws IOException, SVNException {
    context.checkWrite(context.getRepositoryPath(args.path));
  }

  static void writeLock(@NotNull SvnServerWriter writer, @Nullable LockDesc lockDesc) throws IOException {
    if (lockDesc != null)
      writer
          .listBegin()
          .string(lockDesc.getPath())
          .string(lockDesc.getToken())
          .string(lockDesc.getOwner())
          .listBegin().stringNullable(lockDesc.getComment()).listEnd()
          .string(lockDesc.getCreatedString())
          .listBegin()
          .listEnd()
          .listEnd();
  }

  public static final class Params {
    @NotNull
    private final String path;
    @NotNull
    private final String[] comment;
    private final boolean stealLock;
    @NotNull
    private final int[] rev;

    public Params(@NotNull String path, @NotNull String[] comment, boolean stealLock, @NotNull int[] rev) {
      this.path = path;
      this.comment = comment;
      this.stealLock = stealLock;
      this.rev = rev;
    }
  }
}
