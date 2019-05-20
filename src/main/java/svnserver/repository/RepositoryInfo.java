/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.repository;

import org.jetbrains.annotations.NotNull;
import org.tmatesoft.svn.core.SVNURL;
import svnserver.repository.git.GitBranch;

/**
 * Mapped repository info.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public final class RepositoryInfo {
  @NotNull
  private final SVNURL baseUrl;
  @NotNull
  private final GitBranch branch;

  public RepositoryInfo(@NotNull SVNURL baseUrl, @NotNull GitBranch branch) {
    this.baseUrl = baseUrl;
    this.branch = branch;
  }

  @NotNull
  public SVNURL getBaseUrl() {
    return baseUrl;
  }

  @NotNull
  public GitBranch getBranch() {
    return branch;
  }
}
