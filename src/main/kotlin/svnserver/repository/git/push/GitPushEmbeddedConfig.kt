/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.repository.git.push

import svnserver.config.GitPusherConfig
import svnserver.context.LocalContext

/**
 * Git push by embedded git client.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
class GitPushEmbeddedConfig : GitPusherConfig {
    private var hooksPath: String? = null
    private var useHooksDir: Boolean = false

    override fun create(context: LocalContext): GitPusher {
        return GitPushEmbedded(context, hooksPath, useHooksDir)
    }

    companion object {
        val instance: GitPushEmbeddedConfig = GitPushEmbeddedConfig()
    }
}
