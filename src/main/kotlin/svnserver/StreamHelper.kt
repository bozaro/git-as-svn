/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver

import java.io.IOException
import java.io.InputStream

/**
 * Usefull stream methods.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
object StreamHelper {
    @Throws(IOException::class)
    fun readFully(inputStream: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        var totalRead = 0
        while (totalRead < length) {
            val read: Int = inputStream.read(buffer, offset + totalRead, length - totalRead)
            if (read <= 0) {
                break
            }
            totalRead += read
        }
        return totalRead
    }
}
