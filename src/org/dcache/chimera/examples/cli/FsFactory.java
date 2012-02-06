/*
 * This library is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Library General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this program (see the file COPYING.LIB for more
 * details); if not, write to the Free Software Foundation, Inc.,
 * 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.dcache.chimera.examples.cli;

import org.dcache.chimera.FileSystemProvider;
import org.dcache.chimera.JdbcFs;

import com.jolbox.bonecp.BoneCPDataSource;

public class FsFactory {

    public static final String USAGE = "<jdbcDrv> <jdbcUrl> <dbDialect> <dbUser> <dbPass>";
    public static final int ARGC = 5;
    public static FileSystemProvider createFileSystem(String[] args) throws Exception {

        if (args.length < 5) {
            throw new IllegalArgumentException();
        }

        String jdbcDrv = args[0];
        String jdbcUrl = args[1];
        String dbDialect = args[2];
        String dbUser = args[3];
        String dbPass = args[4];

        Class.forName(jdbcDrv);

        BoneCPDataSource ds = new BoneCPDataSource();
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(dbUser);
        ds.setPassword(dbPass);
        ds.setIdleConnectionTestPeriodInMinutes(60);
        ds.setIdleMaxAgeInMinutes(240);
        ds.setMaxConnectionsPerPartition(30);
        ds.setMaxConnectionsPerPartition(10);
        ds.setPartitionCount(3);
        ds.setAcquireIncrement(5);
        ds.setStatementsCacheSize(100);
        ds.setReleaseHelperThreads(3);

        return new JdbcFs(ds, dbDialect);
    }
}