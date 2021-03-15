/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.tester

import org.tmatesoft.svn.core.SVNErrorCode
import org.tmatesoft.svn.core.SVNErrorMessage
import org.tmatesoft.svn.core.SVNException
import org.tmatesoft.svn.core.SVNURL
import org.tmatesoft.svn.core.io.SVNRepository
import org.tmatesoft.svn.core.io.SVNRepositoryFactory
import svnserver.SvnTestServer
import svnserver.TestHelper
import java.io.IOException
import java.nio.file.Path

/**
 * SvnKit subversion implementation.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
class SvnTesterSvnKit : SvnTester {
    private var repoDir: Path
    private var _url: SVNURL
    override val url: SVNURL
        get() = _url
    override fun openSvnRepository(): SVNRepository {
        return SvnTestServer.openSvnRepository(url, USER_NAME, PASSWORD)
    }
    override fun close() {
        TestHelper.deleteDirectory(repoDir)
    }

    companion object {
        private const val USER_NAME = "tester"
        private const val PASSWORD = "passw0rd"
    }

    init {
        try {
            repoDir = TestHelper.createTempDir("git-as-svn")
            _url = SVNRepositoryFactory.createLocalRepository(repoDir.toFile(), true, true)
        } catch (e: IOException) {
            throw SVNException(SVNErrorMessage.create(SVNErrorCode.IO_ERROR, e))
        }
    }
}
