/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.repository.git.path

/**
 * Interface for path matching.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
interface PathMatcher {
    fun createChild(name: String, isDir: Boolean): PathMatcher?
    val isMatch: Boolean
    val svnMaskLocal: String?
        get() {
            return null
        }
    val svnMaskGlobal: String?
        get() {
            return null
        }
}
