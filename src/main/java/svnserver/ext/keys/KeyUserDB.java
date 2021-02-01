/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.ext.keys;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tmatesoft.svn.core.SVNException;
import svnserver.auth.Authenticator;
import svnserver.auth.User;
import svnserver.auth.UserDB;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

public final class KeyUserDB implements UserDB {

  private final UserDB internal;
  private final KeyAuthenticator keyAuthenticator;

  public KeyUserDB(UserDB internal, String secretToken) {
    this.internal = internal;
    this.keyAuthenticator = new KeyAuthenticator(internal, secretToken);
  }

  @Override
  public @NotNull Collection<Authenticator> authenticators() {
    ArrayList<Authenticator> authenticators = new ArrayList<>(internal.authenticators());
    authenticators.add(keyAuthenticator);

        return Collections.unmodifiableList(authenticators);
    }

    @Override
    public User check(@NotNull String username, @NotNull String password) throws SVNException {
        return internal.check(username, password);
    }

    @Override
	public @Nullable User lookupByUserName(@NotNull String username) throws SVNException {
		return internal.lookupByUserName(username);
	}

	@Override
	public @Nullable User lookupByExternal(@NotNull String external) throws SVNException {
		return internal.lookupByExternal(external);
	}
    
}