/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.repository.mapping;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.testng.Assert;
import org.testng.annotations.Test;
import svnserver.repository.RepositoryMapping;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Simple test for repository mapping.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public class RepositoryListMappingTest {
  @Test
  public void testEmpty() {
    final TreeMap<String, String> map = new Builder()
        .add("/")
        .build();
    checkMapped(map, "/", "");
    checkMapped(map, "/", "/foo");
    checkMapped(map, "/", "/bar");
  }

  private void checkMapped(@NotNull NavigableMap<String, String> map, @Nullable String expected, @NotNull String prefix) {
    final Map.Entry<String, String> entry = RepositoryMapping.getMapped(map, prefix);
    if (expected == null) {
      Assert.assertNull(entry, prefix);
    } else {
      Assert.assertNotNull(entry, prefix);
      Assert.assertEquals(expected, entry.getKey(), prefix);
    }
  }

  @Test
  public void testRepositoryByPrefix() {
    final TreeMap<String, String> map = new Builder()
        .add("/foo/")
        .add("/bar/")
        .add("/foo.test/")
        .build();
    checkMapped(map, null, "");
    checkMapped(map, null, "/bazz");
    checkMapped(map, null, "/foo2");
    checkMapped(map, null, "/bar2");
    checkMapped(map, "/foo/", "/foo");
    checkMapped(map, "/foo/", "/foo/bar");
    checkMapped(map, "/bar/", "/bar");
    checkMapped(map, "/bar/", "/bar/foo");
    checkMapped(map, "/foo.test/", "/foo.test");
    checkMapped(map, "/foo.test/", "/foo.test/foo");
  }

  public static class Builder {
    @NotNull
    private final Map<String, String> mapping = new TreeMap<>();

    public Builder add(@NotNull String prefix) {
      mapping.put(prefix, prefix);
      return this;
    }

    TreeMap<String, String> build() {
      return new TreeMap<>(mapping);
    }
  }
}
