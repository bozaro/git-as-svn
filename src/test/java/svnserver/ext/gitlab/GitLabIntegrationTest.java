/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.ext.gitlab;

import org.gitlab.api.GitlabAPI;
import org.gitlab.api.http.Query;
import org.gitlab.api.models.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Ignore;
import org.testng.annotations.Test;
import org.tmatesoft.svn.core.SVNAuthenticationException;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.io.SVNRepository;
import svnserver.StreamHelper;
import svnserver.SvnTestHelper;
import svnserver.SvnTestServer;
import svnserver.auth.User;
import svnserver.config.RepositoryMappingConfig;
import svnserver.ext.gitlab.auth.GitLabUserDBConfig;
import svnserver.ext.gitlab.config.GitLabConfig;
import svnserver.ext.gitlab.config.GitLabContext;
import svnserver.ext.gitlab.config.GitLabToken;
import svnserver.ext.gitlab.mapping.GitLabMappingConfig;
import svnserver.ext.gitlfs.storage.LfsStorage;
import svnserver.ext.gitlfs.storage.LfsWriter;
import svnserver.repository.git.GitCreateMode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Set;
import java.util.function.Function;

/**
 * @author Marat Radchenko <marat@slonopotamus.org>
 */
public final class GitLabIntegrationTest {

  @NotNull
  private static final String root = "root";
  @NotNull
  private static final String rootPassword = "12345678";

  @NotNull
  private static final String user = "git-as-svn";
  @NotNull
  private static final String userPassword = "git-as-svn";

  private GenericContainer<?> gitlab;
  private String gitlabUrl;
  private GitLabToken rootToken;
  private GitlabProject gitlabProject;
  private GitlabProject gitlabPublicProject;

  @BeforeClass
  void before() throws Exception {
    SvnTestHelper.skipTestIfDockerUnavailable();

    String gitlabVersion = System.getenv("GITLAB_VERSION");
    if (gitlabVersion == null) {
      if (System.getenv("TRAVIS") != null)
        throw new SkipException("Only run gitlab tests on Travis when explicitly asked");

      gitlabVersion = "latest";
    }

    final int hostGitLabPort = 9999;
    // This is supposed to be 80, but GitLab binds to port from external_url
    // See https://stackoverflow.com/questions/39351563/gitlab-docker-not-working-if-external-url-is-set
    final int containerGitLabPort = 9999;

    gitlab = new FixedHostPortGenericContainer<>("gitlab/gitlab-ce:" + gitlabVersion)
        // We have a chicken-and-egg problem here. In order to set external_url, we need to know container address,
        // but we do not know container address until container is started.
        // So, for now use fixed port :(
        .withFixedExposedPort(hostGitLabPort, containerGitLabPort)
        // This is kinda stupid that we need to do withExposedPorts even when we have withFixedExposedPort
        .withExposedPorts(containerGitLabPort)
        .withEnv("GITLAB_OMNIBUS_CONFIG", String.format("external_url 'http://localhost:%s/'", hostGitLabPort))
        .withEnv("GITLAB_ROOT_PASSWORD", rootPassword)
        .waitingFor(Wait.forHttp("/users/sign_in")
            .withStartupTimeout(Duration.of(10, ChronoUnit.MINUTES)));

    gitlab.start();
    gitlabUrl = "http://" + gitlab.getContainerIpAddress() + ":" + gitlab.getMappedPort(containerGitLabPort);

    rootToken = createToken(root, rootPassword, true);

    final GitlabAPI rootAPI = GitLabContext.connect(gitlabUrl, rootToken);

    final GitlabUser gitlabUser = rootAPI.createUser(new CreateUserRequest(user, user, "git-as-svn@localhost").setPassword(userPassword));
    Assert.assertNotNull(gitlabUser);

    final GitlabGroup group = rootAPI.createGroup(new CreateGroupRequest("testGroup").setVisibility(GitlabVisibility.PUBLIC), null);
    Assert.assertNotNull(group);

    Assert.assertNotNull(rootAPI.addGroupMember(group.getId(), gitlabUser.getId(), GitlabAccessLevel.Developer));

    gitlabProject = createGitlabProject(rootAPI, group, "test", GitlabVisibility.INTERNAL, Collections.singleton("git-as-svn"));
    gitlabPublicProject = createGitlabProject(rootAPI, group, "publik", GitlabVisibility.PUBLIC, Collections.emptySet());
  }

  @NotNull
  private GitLabToken createToken(@NotNull String username, @NotNull String password, boolean sudoScope) throws IOException {
    return GitLabContext.obtainAccessToken(gitlabUrl, username, password, sudoScope);
  }

  @NotNull
  private GitlabProject createGitlabProject(@NotNull GitlabAPI rootAPI, @NotNull GitlabGroup group, @NotNull String name, @NotNull GitlabVisibility visibility, @NotNull Set<String> tags) throws IOException {
    // java-gitlab-api doesn't handle tag_list, so we have to do this manually
    final Query query = new Query()
        .append("name", name)
        .appendIf("namespace_id", group.getId())
        .appendIf("visibility", visibility.toString())
        .appendIf("tag_list", String.join(",", tags));

    final String tailUrl = GitlabProject.URL + query.toString();
    return rootAPI.dispatch().to(tailUrl, GitlabProject.class);
  }

  @AfterClass
  void after() {
    if (gitlab != null) {
      gitlab.stop();
      gitlab = null;
    }
  }

  @Test
  void validUser() throws Exception {
    checkUser(root, rootPassword);
  }

  private void checkUser(@NotNull String login, @NotNull String password) throws Exception {
    try (SvnTestServer server = createServer(rootToken, null)) {
      server.openSvnRepository(login, password).getLatestRevision();
    }
  }

  @NotNull
  private SvnTestServer createServer(@NotNull GitLabToken token, @Nullable Function<File, RepositoryMappingConfig> mappingConfigCreator) throws Exception {
    final GitLabConfig gitLabConfig = new GitLabConfig(gitlabUrl, token);
    return SvnTestServer.createEmpty(new GitLabUserDBConfig(), mappingConfigCreator, false, false, gitLabConfig);
  }

  @Test
  void invalidPassword() {
    Assert.expectThrows(SVNAuthenticationException.class, () -> checkUser(root, "wrongpassword"));
  }

  @Test
  void invalidUser() {
    Assert.expectThrows(SVNAuthenticationException.class, () -> checkUser("wronguser", rootPassword));
  }

  @Test
  void gitlabMappingAsRoot() throws Exception {
    try (SvnTestServer server = createServer(rootToken, dir -> new GitLabMappingConfig(dir, GitCreateMode.EMPTY))) {
      openSvnRepository(server, gitlabProject, user, userPassword).getLatestRevision();
    }
  }

  @NotNull
  private SVNRepository openSvnRepository(@NotNull SvnTestServer server, @NotNull GitlabProject gitlabProject, @NotNull String username, @NotNull String password) throws SVNException {
    return SvnTestServer.openSvnRepository(server.getUrl(false).appendPath(gitlabProject.getPathWithNamespace() + "/master", false), username, password);
  }

  @Test
  void uploadToLfs() throws Exception {
    final @NotNull LfsStorage storage = GitLabConfig.createLfsStorage(gitlabUrl + "/", gitlabProject.getPathWithNamespace(), root, rootPassword);
    final String expected = "hello 12345";

    final String oid;
    try (LfsWriter writer = storage.getWriter(User.create(root, root, root, root))) {
      writer.write(expected.getBytes(StandardCharsets.UTF_8));
      oid = writer.finish(null);
    }

    final byte[] buff = new byte[10240];
    final int length;
    try (@NotNull InputStream reader = storage.getReader(oid).openStream()) {
      length = StreamHelper.readFully(reader, buff, 0, buff.length);
    }

    final String actual = new String(buff, 0, length, StandardCharsets.UTF_8);
    Assert.assertEquals(actual, expected);
  }

  @Test
  void gitlabTagMappingPositive() throws Exception {
    testTagMapping(gitlabProject);
  }

  private void testTagMapping(@NotNull GitlabProject project) throws Exception {
    try (SvnTestServer server = createServer(rootToken, dir -> new GitLabMappingConfig(dir, GitCreateMode.EMPTY, Collections.singleton("git-as-svn")))) {
      openSvnRepository(server, project, user, userPassword).getLatestRevision();
    }
  }

  @Test
  void gitlabTagMappingNegative() throws Exception {
    try {
      testTagMapping(gitlabPublicProject);
    } catch (SVNException e) {
      if (e.getErrorMessage().getErrorCode() != SVNErrorCode.RA_SVN_REPOS_NOT_FOUND)
        throw e;
    }
  }

  @Test
  void gitlabMappingForAnonymous() throws Throwable {
    try (SvnTestServer server = createServer(rootToken, dir -> new GitLabMappingConfig(dir, GitCreateMode.EMPTY))) {
      openSvnRepository(server, gitlabPublicProject, "nobody", "nopassword").getLatestRevision();
    }
  }

  /**
   * Test for #119.
   */
  @Ignore
  @Test
  void gitlabMappingAsUser() throws Exception {
    final GitLabToken userToken = createToken(user, userPassword, false);

    try (SvnTestServer server = createServer(userToken, dir -> new GitLabMappingConfig(dir, GitCreateMode.EMPTY))) {
      openSvnRepository(server, gitlabProject, root, rootPassword).getLatestRevision();
    }
  }
}
