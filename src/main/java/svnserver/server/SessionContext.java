package svnserver.server;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import svnserver.StringHelper;
import svnserver.auth.User;
import svnserver.parser.SvnServerParser;
import svnserver.parser.SvnServerWriter;
import svnserver.repository.VcsFile;
import svnserver.repository.VcsRepository;
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
  private final SvnServerParser parser;
  @NotNull
  private final SvnServerWriter writer;
  @NotNull
  private final Deque<Step> stepStack = new ArrayDeque<>();
  @NotNull
  private final SvnServer server;
  @NotNull
  private final SVNURL baseUrl;
  @NotNull
  private final Set<String> capabilities;
  @NotNull
  private final User user;
  @NotNull
  private String parent;

  public SessionContext(@NotNull SvnServerParser parser,
                        @NotNull SvnServerWriter writer,
                        @NotNull SvnServer server,
                        @NotNull SVNURL baseUrl,
                        @NotNull ClientInfo clientInfo,
                        @NotNull User user) throws SVNException {
    this.parser = parser;
    this.writer = writer;
    this.server = server;
    this.user = user;
    this.baseUrl = SVNURL.create(baseUrl.getProtocol(), null, baseUrl.getHost(), baseUrl.getPort(), "", false);
    setParent(clientInfo.getUrl());
    this.capabilities = new HashSet<>(Arrays.asList(clientInfo.getCapabilities()));
  }

  @NotNull
  public SVNURL getBaseUrl() {
    return baseUrl;
  }

  public boolean hasCapability(@NotNull String capability) {
    return capabilities.contains(capability);
  }

  public void setParent(@NotNull SVNURL url) throws SVNException {
    this.parent = getRepositoryPath(url);
  }

  @NotNull
  public String getRepositoryPath(@NotNull String localPath) throws SVNException {
    return StringHelper.joinPath(parent, localPath);
  }

  @NotNull
  private String getRepositoryPath(@NotNull SVNURL url) throws SVNException {
    final String root = baseUrl.getPath();
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

  @NotNull
  public User getUser() {
    return user;
  }

  @NotNull
  public VcsRepository getRepository() {
    return server.getRepository();
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

  @Nullable
  public Step poll() {
    return stepStack.poll();
  }

  /**
   * Get repository file.
   *
   * @param rev  Target revision.
   * @param path Target path or url.
   * @return Return file object.
   * @throws SVNException
   * @throws IOException
   */
  @Nullable
  public VcsFile getFile(int rev, @NotNull String path) throws SVNException, IOException {
    return getRepository().getRevisionInfo(rev).getFile(getRepositoryPath(path));
  }

  @Nullable
  public VcsFile getFile(int rev, @NotNull SVNURL url) throws SVNException, IOException {
    final String path = getRepositoryPath(url);
    checkAcl(path);
    return getRepository().getRevisionInfo(rev).getFile(path);
  }

  public void checkAcl(@NotNull String path) throws SVNException {
    server.getAcl().check(user, path);
  }
}
