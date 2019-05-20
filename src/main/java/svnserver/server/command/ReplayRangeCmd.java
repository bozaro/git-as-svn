/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.server.command;

import org.jetbrains.annotations.NotNull;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import svnserver.parser.SvnServerWriter;
import svnserver.repository.git.GitRevision;
import svnserver.server.SessionContext;

import java.io.IOException;

/**
 * Send revisions as is.
 * <p>
 * <pre>
 * replay-range
 *    params:    ( start-rev:number end-rev:number low-water-mark:number
 *                 send-deltas:bool )
 *    After auth exchange completes, server sends each revision
 *    from start-rev to end-rev, alternating between sending 'revprops'
 *    entries and sending the revision in the editor command set.
 *    After all revisions are complete, server sends response.
 *    revprops:  ( revprops:word props:proplist )
 *      (revprops here is the literal word "revprops".)
 *    response   ( )
 * </pre>
 *
 * @author a.navrotskiy
 */
public final class ReplayRangeCmd extends BaseCmd<ReplayRangeCmd.Params> {
  @NotNull
  @Override
  public Class<Params> getArguments() {
    return Params.class;
  }

  @Override
  protected void processCommand(@NotNull SessionContext context, @NotNull Params args) throws IOException, SVNException {
    if (args.startRev > args.endRev) {
      throw new SVNException(SVNErrorMessage.create(SVNErrorCode.UNKNOWN, "Invalid revision range: start: " + args.startRev + ", end " + args.endRev));
    }
    final SvnServerWriter writer = context.getWriter();
    for (int revision = args.startRev; revision <= args.endRev; revision++) {
      final GitRevision revisionInfo = context.getBranch().getRevisionInfo(revision);
      writer
          .listBegin()
          .word("revprops")
          .writeMap(revisionInfo.getProperties(true))
          .listEnd();
      ReplayCmd.replayRevision(context, revision, args.lowRevision, args.sendDeltas);
    }
    writer
        .listBegin()
        .word("success")
        .listBegin().listEnd()
        .listEnd();
  }

  public static class Params {
    private final int startRev;
    private final int endRev;
    private final int lowRevision;
    private final boolean sendDeltas;

    public Params(int startRev, int endRev, int lowRevision, boolean sendDeltas) {
      this.startRev = startRev;
      this.endRev = endRev;
      this.lowRevision = lowRevision;
      this.sendDeltas = sendDeltas;
    }
  }
}
