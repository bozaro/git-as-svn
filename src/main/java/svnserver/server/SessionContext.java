/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.server;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import svnserver.StringHelper;
import svnserver.auth.User;
import svnserver.parser.SvnServerParser;
import svnserver.parser.SvnServerWriter;
import svnserver.repository.RepositoryInfo;
import svnserver.repository.VcsAccess;
import svnserver.repository.git.GitBranch;
import svnserver.repository.git.GitFile;
import svnserver.server.command.BaseCmd;
import svnserver.server.msg.ClientInfo;
import svnserver.server.step.Step;

import java.io.IOException;
import java.util.*;

/**
 * SVN client session context.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public final class SessionContext {

  @NotNull
  private static final Logger log = LoggerFactory.getLogger(SessionContext.class);

  @NotNull
  private final SvnServerParser parser;
  @NotNull
  private final SvnServerWriter writer;
  @NotNull
  private final Deque<Step> stepStack = new ArrayDeque<>();
  @NotNull
  private final SvnServer server;
  @NotNull
  private final RepositoryInfo repositoryInfo;
  @NotNull
  private final Set<String> capabilities;
  @NotNull
  private final VcsAccess acl;
  @NotNull
  private User user;
  @NotNull
  private String parent;

  public SessionContext(@NotNull SvnServerParser parser,
                        @NotNull SvnServerWriter writer,
                        @NotNull SvnServer server,
                        @NotNull RepositoryInfo repositoryInfo,
                        @NotNull ClientInfo clientInfo) throws SVNException {
    this.parser = parser;
    this.writer = writer;
    this.server = server;
    this.user = User.getAnonymous();
    this.repositoryInfo = repositoryInfo;
    this.acl = getBranch().getRepository().getContext().sure(VcsAccess.class);
    setParent(clientInfo.getUrl());
    this.capabilities = new HashSet<>(Arrays.asList(clientInfo.getCapabilities()));
  }

  @NotNull
  public GitBranch getBranch() {
    return repositoryInfo.getBranch();
  }

  public void setParent(@NotNull SVNURL url) throws SVNException {
    this.parent = getRepositoryPath(url);
  }

  @NotNull
  private String getRepositoryPath(@NotNull SVNURL url) throws SVNException {
    final String root = repositoryInfo.getBaseUrl().getPath();
    final String path = url.getPath();
    if (!path.startsWith(root)) {
      throw new SVNException(SVNErrorMessage.create(SVNErrorCode.BAD_URL, "Invalid relative path: " + path + " (base: " + root + ")"));
    }
    if (root.length() == path.length()) {
      return "";
    }
    final boolean hasSlash = root.endsWith("/");
    if ((!hasSlash) && (path.charAt(root.length()) != '/')) {
      throw new SVNException(SVNErrorMessage.create(SVNErrorCode.BAD_URL, "Invalid relative path: " + path + " (base: " + root + ")"));
    }
    return StringHelper.normalize(path.substring(root.length()));
  }

  public boolean isCompressionEnabled() {
    return server.isCompressionEnabled() && capabilities.contains("svndiff1");
  }

  public void authenticate(boolean allowAnonymous) throws IOException, SVNException {
    if (!user.isAnonymous()) {
      throw new IllegalStateException();
    }
    this.user = server.authenticate(parser, writer, repositoryInfo, allowAnonymous);
  }

  @NotNull
  public User getUser() {
    return user;
  }

  @NotNull
  public SvnServerParser getParser() {
    return parser;
  }

  @NotNull
  public SvnServerWriter getWriter() {
    return writer;
  }

  public void push(@NotNull Step step) {
    stepStack.push(step);
  }

  @Nullable Step poll() {
    return stepStack.poll();
  }

  /**
   * Get repository file.
   *
   * @param rev  Target revision.
   * @param path Target path or url.
   * @return Return file object.
   */
  @Nullable
  public GitFile getFile(int rev, @NotNull String path) throws SVNException, IOException {
    return getBranch().getRevisionInfo(rev).getFile(getRepositoryPath(path));
  }

  @NotNull
  public String getRepositoryPath(@NotNull String localPath) {
    return StringHelper.joinPath(parent, localPath);
  }

  @Nullable
  public GitFile getFile(int rev, @NotNull SVNURL url) throws SVNException, IOException {
    final String path = getRepositoryPath(url);
    checkRead(path);
    return getBranch().getRevisionInfo(rev).getFile(path);
  }

  public void checkRead(@Nullable String path) throws SVNException, IOException {
    acl.checkRead(user, path);
  }

  public void checkWrite(@Nullable String path) throws SVNException, IOException {
    acl.checkWrite(user, path);
  }

  public void skipUnsupportedCommand(@NotNull String cmd) throws IOException {
    log.error("Unsupported command: {}", cmd);
    BaseCmd.sendError(writer, SVNErrorMessage.create(SVNErrorCode.RA_SVN_UNKNOWN_CMD, "Unsupported command: " + cmd));
    parser.skipItems();
  }
}
