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
package org.dcache.chimera;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.dcache.chimera.posix.Stat;
import org.dcache.chimera.store.AccessLatency;
import org.dcache.chimera.store.InodeStorageInformation;
import org.dcache.chimera.store.RetentionPolicy;
import org.dcache.chimera.util.SqlHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * SQL driver
 */

class FsSqlDriver {


    /**
     * logger
     */
    private static final Logger _log = LoggerFactory.getLogger(FsSqlDriver.class);


    /**
     * default file IO mode
     */

    private final static int IOMODE_ENABLE = 1;
    private final static int IOMODE_DISABLE = 0;

    private final int _ioMode;

    /**
     * this is a utility class which is issues SQL queries on database
     */
    protected FsSqlDriver() {

        if (Boolean.valueOf(System.getProperty("chimera.inodeIoMode")).booleanValue()) {
            _ioMode = IOMODE_ENABLE;
        } else {
            _ioMode = IOMODE_DISABLE;
        }

    }

    private static final String sqlUsedSpace = "SELECT SUM(isize) AS usedSpace FROM t_inodes WHERE itype=32768";

    /**
     * @param dbConnection
     * @return total space used by files
     * @throws SQLException
     */
    long usedSpace(Connection dbConnection) throws SQLException {
        long usedSpace = 0;
        PreparedStatement stUsedSpace = null;
        ResultSet rs = null;
        try {

            stUsedSpace = dbConnection.prepareStatement(sqlUsedSpace);

            rs = stUsedSpace.executeQuery();
            if (rs.next()) {
                usedSpace = rs.getLong("usedSpace");
            }

        } finally {
            SqlHelper.tryToClose(rs);
            SqlHelper.tryToClose(stUsedSpace);
        }

        return usedSpace;
    }


    private static final String sqlUsedFiles = "SELECT count(ipnfsid) AS usedFiles FROM t_inodes WHERE itype=32768";

    /**
     * @param dbConnection
     * @return total number of files
     * @throws SQLException
     */
    long usedFiles(Connection dbConnection) throws SQLException {
        long usedFiles = 0;
        PreparedStatement stUsedFiles = null;
        ResultSet rs = null;
        try {

            stUsedFiles = dbConnection.prepareStatement(sqlUsedFiles);

            rs = stUsedFiles.executeQuery();
            if (rs.next()) {
                usedFiles = rs.getLong("usedFiles");
            }

        } finally {
            SqlHelper.tryToClose(rs);
            SqlHelper.tryToClose(stUsedFiles);
        }

        return usedFiles;
    }

    /**
     * creates a new inode and an entry name in parent directory.
     * Parent reference count and modification time is updated.
     *
     * @param dbConnection
     * @param parent
     * @param name
     * @param owner
     * @param group
     * @param mode
     * @param type
     * @return
     * @throws ChimeraFsException
     * @throws java.sql.SQLException
     */
    FsInode createFile(Connection dbConnection, FsInode parent, String name, int owner, int group, int mode, int type) throws ChimeraFsException, SQLException {

        FsInode inode = null;

        inode = new FsInode(parent.getFs());
        createFileWithId(dbConnection, parent, inode, name, owner, group, mode, type);

        return inode;
    }


    /**
     * Creates a new entry with given inode is in parent directory.
     * Parent reference count and modification time is updated.
     *
     * @param dbConnection
     * @param inode
     * @param parent
     * @param name
     * @param owner
     * @param group
     * @param mode
     * @param type
     * @return
     * @throws ChimeraFsException
     * @throws java.sql.SQLException
     */
    FsInode createFileWithId(Connection dbConnection, FsInode parent, FsInode inode, String name, int owner, int group, int mode, int type) throws ChimeraFsException, SQLException {

        createInode(dbConnection, inode, type, owner, group, mode, 1);
        createEntryInParent(dbConnection, parent, name, inode);
        incNlink(dbConnection, parent);
        setFileMTime(dbConnection, parent, 0, System.currentTimeMillis());

        return inode;
    }

    private static final String sqlListDir = "SELECT * FROM t_dirs WHERE iparent=?";

    /**
     * returns list of files in the directory. If there is no entries,
     * empty list is returned. inode is not tested to be a directory
     *
     * @param dbConnection
     * @param dir
     * @return
     * @throws java.sql.SQLException
     */
    String[] listDir(Connection dbConnection, FsInode dir) throws SQLException {

        String[] list = null;
        ResultSet result = null;
        PreparedStatement stListDirectory = null;

        try {

            stListDirectory = dbConnection.prepareStatement(sqlListDir);
            stListDirectory.setString(1, dir.toString());
            stListDirectory.setFetchSize(1000);
            result = stListDirectory.executeQuery();


            List<String> directoryList = new ArrayList<String>();
            while (result.next()) {
                directoryList.add(result.getString("iname"));
            }

            list = directoryList.toArray(new String[directoryList.size()]);
        } finally {
            SqlHelper.tryToClose(result);
            SqlHelper.tryToClose(stListDirectory);
        }

        return list;
    }


    private static final String sqlListDirFull = "SELECT " + "t_inodes.ipnfsid, t_dirs.iname, t_inodes.isize,t_inodes.inlink,t_inodes.imode,t_inodes.itype,t_inodes.iuid,t_inodes.igid,t_inodes.iatime,t_inodes.ictime,t_inodes.imtime  " + "FROM t_inodes, t_dirs WHERE iparent=? AND t_inodes.ipnfsid = t_dirs.ipnfsid";

    /**
     * the same as listDir, but array of {@HimeraDirectoryEntry} is returned, which contains
     * file attributes as well.
     *
     * @param dbConnection
     * @param dir
     * @return
     * @throws java.sql.SQLException
     */
    DirectoryStreamB<HimeraDirectoryEntry> newDirectoryStream(Connection dbConnection, FsInode dir) throws SQLException {

        ResultSet result = null;
        PreparedStatement stListDirectoryFull = null;

        stListDirectoryFull = dbConnection.prepareStatement(sqlListDirFull);
        stListDirectoryFull.setFetchSize(50);
        stListDirectoryFull.setString(1, dir.toString());

        result = stListDirectoryFull.executeQuery();
        return new DirectoryStreamImpl(dir, dbConnection, stListDirectoryFull, result);
        /*
        * DB resources freed by
        * DirectoryStreamB.close()
        */
    }


    boolean remove(Connection dbConnection, FsInode parent, String name) throws ChimeraFsException, SQLException {

        FsInode inode = inodeOf(dbConnection, parent, name);


        if (inode.isDirectory()) {

            return removeDir(dbConnection, parent, inode, name);

        }

        return removeFile(dbConnection, parent, inode, name);

    }

    private boolean removeDir(Connection dbConnection, FsInode parent, FsInode inode, String name) throws ChimeraFsException, SQLException {

        Stat dirStat = inode.statCache();
        if (dirStat.getNlink() > 2) {
            throw new ChimeraFsException("directory is not empty");
        }

        removeEntryInParent(dbConnection, inode, ".");
        removeEntryInParent(dbConnection, inode, "..");
        // decrease reference count ( '.' , '..', and in parent directory ,
        // and inode itself)
        decNlink(dbConnection, inode, 2);
        removeTag(dbConnection, inode);

        removeEntryInParent(dbConnection, parent, name);
        decNlink(dbConnection, parent);
        setFileMTime(dbConnection, parent, 0, System.currentTimeMillis());

        return removeInode(dbConnection, inode);

    }


    private boolean removeFile(Connection dbConnection, FsInode parent, FsInode inode, String name) throws ChimeraFsException, SQLException {

        boolean isLast = inode.stat().getNlink() == 1;

        decNlink(dbConnection, inode);
        removeEntryInParent(dbConnection, parent, name);
        decNlink(dbConnection, parent);
        setFileMTime(dbConnection, parent, 0, System.currentTimeMillis());

        if (isLast) {
            // it's the last reference

            /*
            * TODO: put into trash
            */
            for (int i = 1; i <= 7; i++) {
                removeInodeLevel(dbConnection, inode, i);
            }

            return removeInode(dbConnection, inode);
        }

        return true;

    }


    boolean remove(Connection dbConnection, FsInode parent, FsInode inode) throws ChimeraFsException, SQLException {

        if (inode.isDirectory()) {

            Stat dirStat = inode.statCache();
            if (dirStat.getNlink() > 2) {
                throw new ChimeraFsException("directory is not empty");
            }
            removeEntryInParent(dbConnection, inode, ".");
            removeEntryInParent(dbConnection, inode, "..");
            // decrease reference count ( '.' , '..', and in parent directory ,
            // and inode itself)
            decNlink(dbConnection, inode, 2);
            removeTag(dbConnection, inode);

        } else {
            decNlink(dbConnection, inode);

            /*
                * TODO: put into trash
                */
            for (int i = 1; i <= 7; i++) {
                removeInodeLevel(dbConnection, inode, i);
            }
        }

        removeEntryInParentByID(dbConnection, parent, inode);
        decNlink(dbConnection, parent);

        setFileMTime(dbConnection, parent, 0, System.currentTimeMillis());
        removeStorageInfo(dbConnection, inode);

        return removeInode(dbConnection, inode);
    }

    public Stat stat(Connection dbConnection, FsInode inode) throws SQLException {
        return stat(dbConnection, inode, 0);
    }


    private static final String sqlStat = "SELECT isize,inlink,itype,imode,iuid,igid,iatime,ictime,imtime FROM t_inodes WHERE ipnfsid=?";

    public Stat stat(Connection dbConnection, FsInode inode, int level) throws SQLException {

        org.dcache.chimera.posix.Stat ret = null;
        PreparedStatement stStatInode = null;
        ResultSet statResult = null;
        try {

            if (level == 0) {
                stStatInode = dbConnection.prepareStatement(sqlStat);

            } else {
                stStatInode = dbConnection.prepareStatement("SELECT isize,inlink,imode,iuid,igid,iatime,ictime,imtime FROM t_level_" + level + " WHERE ipnfsid=?");
            }

            stStatInode.setString(1, inode.toString());
            statResult = stStatInode.executeQuery();

            if (statResult.next()) {
                int inodeType;

                if (level == 0) {
                    inodeType = statResult.getInt("itype");
                } else {
                    inodeType = UnixPermission.S_IFREG;
                }

                ret = new org.dcache.chimera.posix.Stat();
                ret.setSize(statResult.getLong("isize"));
                ret.setATime(statResult.getTimestamp("iatime").getTime());
                ret.setCTime(statResult.getTimestamp("ictime").getTime());
                ret.setMTime(statResult.getTimestamp("imtime").getTime());
                ret.setUid(statResult.getInt("iuid"));
                ret.setGid(statResult.getInt("igid"));
                ret.setMode(statResult.getInt("imode") | inodeType);
                ret.setNlink(statResult.getInt("inlink"));
                ret.setIno((int) inode.id());
                ret.setDev(17);
            }

        } finally {
            SqlHelper.tryToClose(statResult);
            SqlHelper.tryToClose(stStatInode);
        }

        return ret;
    }

    /**
     * create a new directory in parent with name. The reference count if parent directory
     * as well modification time and reference count of newly created directory are updated.
     *
     * @param dbConnection
     * @param parent
     * @param name
     * @param owner
     * @param group
     * @param mode
     * @return
     * @throws ChimeraFsException
     * @throws java.sql.SQLException
     */
    FsInode mkdir(Connection dbConnection, FsInode parent, String name, int owner, int group, int mode) throws ChimeraFsException, SQLException {

        // if exist table parent_dir create an entry

        FsInode inode = null;

        if (parent.isDirectory()) {

            inode = new FsInode(parent.getFs());

            // as soon as directory is created nlink == 2
            createInode(dbConnection, inode, UnixPermission.S_IFDIR, owner, group, mode, 2);
            createEntryInParent(dbConnection, parent, name, inode);

            // increase parent nlink only
            incNlink(dbConnection, parent);

            createEntryInParent(dbConnection, inode, ".", inode);
            createEntryInParent(dbConnection, inode, "..", parent);

        } else {
            throw new NotDirChimeraException(parent);
        }

        return inode;
    }

    /**
     * Make a directory only if it does not already exist within the given
     * parent directory.
     *
     * @param dbConnection Connection to use.
     * @param parent       Parent directory, of which the directory should become a
     *                     subdirectory
     * @param name         Name of the new directory
     * @param owner        UID of the owner
     * @param group        GID of the directory's group
     * @param mode         access mode
     * @return Inode of the existing directory, or of the new one, if it has
     *         to be created
     * @throws ChimeraFsException
     * @throws SQLException
     */
    FsInode mkdirIfNotExists(Connection dbConnection, FsInode parent, String name, int owner, int group, int mode) throws ChimeraFsException, SQLException {
        FsInode inode = null;

        inode = inodeOf(dbConnection, parent, name);

        if (inode == null) {
            return mkdir(dbConnection, parent, name, owner, group, mode);
        } else {
            return inode;
        }
    }


    private static final String sqlMove = "UPDATE t_dirs SET iparent=?, iname=? WHERE iparent=? AND iname=?";

    /**
     * move source from srcDir into dest in destDir.
     * The reference counts if srcDir and destDir is updates.
     *
     * @param dbConnection
     * @param srcDir
     * @param source
     * @param destDir
     * @param dest
     * @throws java.sql.SQLException
     */
    void move(Connection dbConnection, FsInode srcDir, String source, FsInode destDir, String dest) throws SQLException {

        PreparedStatement stMove = null;

        try {


            FsInode destInode = inodeOf(dbConnection, destDir, dest);
            FsInode srcInode = inodeOf(dbConnection, srcDir, source);

            if (destInode != null) {

                if (destInode.equals(srcInode)) {
                    // according to POSIX, we are done
                    return;
                }

                // remove old entry if exist
                removeEntryInParent(dbConnection, destDir, dest);
                decNlink(dbConnection, destInode);
                removeInode(dbConnection, destInode);
            } else {
                incNlink(dbConnection, destDir);
            }


            stMove = dbConnection.prepareStatement(sqlMove);

            stMove.setString(1, destDir.toString());
            stMove.setString(2, dest);
            stMove.setString(3, srcDir.toString());
            stMove.setString(4, source);
            stMove.executeUpdate();

            decNlink(dbConnection, srcDir);

        } finally {
            SqlHelper.tryToClose(stMove);
        }

    }


    private static final String sqlInodeOf = "SELECT ipnfsid FROM t_dirs WHERE iname=? AND iparent=?";

    /**
     * return the inode of path in directory. In case of pnfs magic commands ( '.(' )
     * command specific inode is returned.
     *
     * @param dbConnection
     * @param parent
     * @param name
     * @return null if path is not found
     * @throws java.sql.SQLException
     */
    FsInode inodeOf(Connection dbConnection, FsInode parent, String name) throws SQLException {

        FsInode inode = null;
        String id = null;
        PreparedStatement stGetInodeByName = null;

        ResultSet result = null;
        try {

            stGetInodeByName = dbConnection.prepareStatement(sqlInodeOf);
            stGetInodeByName.setString(1, name);
            stGetInodeByName.setString(2, parent.toString());

            result = stGetInodeByName.executeQuery();

            if (result.next()) {
                id = result.getString("ipnfsid");
            }

        } finally {
            SqlHelper.tryToClose(result);
            SqlHelper.tryToClose(stGetInodeByName);
        }

        if (id != null) {
            inode = new FsInode(parent.getFs(), id);
        }
        return inode;
    }

    private static final String sqlInode2Path_name = "SELECT iname FROM t_dirs WHERE ipnfsid=? AND iparent=? and iname !='.' and iname != '..'";
    private static final String sqlInode2Path_inode = "SELECT iparent FROM t_dirs WHERE ipnfsid=?  and iname != '.' and iname != '..'";

    /**
     * return the path associated with inode, starting from root of the tree.
     * in case of hard link, one of the possible paths is returned
     *
     * @param dbConnection
     * @param inode
     * @param startFrom    defined the "root"
     * @return
     * @throws java.sql.SQLException
     */
    String inode2path(Connection dbConnection, FsInode inode, FsInode startFrom, boolean inclusive) throws SQLException {

        String path = null;
        PreparedStatement ps = null;

        try {

            List<String> pList = new ArrayList<String>();
            String parentId = getParentOf(dbConnection, inode).toString();
            String elementId = inode.toString();

            boolean done = false;
            do {

                ps = dbConnection.prepareStatement(sqlInode2Path_name);
                ps.setString(1, elementId);
                ps.setString(2, parentId);

                ResultSet pSearch = ps.executeQuery();
                if (pSearch.next()) {
                    pList.add(pSearch.getString("iname"));
                }
                elementId = parentId;

                SqlHelper.tryToClose(ps);
                if (inclusive && elementId.equals(startFrom.toString())) {
                    done = true;
                }

                ps = dbConnection.prepareStatement(sqlInode2Path_inode);
                ps.setString(1, parentId);

                pSearch = ps.executeQuery();

                if (pSearch.next()) {
                    parentId = pSearch.getString("iparent");
                }
                ps.close();

                if (!inclusive && parentId.equals(startFrom.toString())) {
                    done = true;
                }
            } while (!done);


            StringBuilder sb = new StringBuilder();

            for (int i = pList.size(); i > 0; i--) {
                sb.append("/").append(pList.get(i - 1));
            }

            path = sb.toString();

        } finally {
            SqlHelper.tryToClose(ps);
        }

        return path;
    }


    private static final String sqlCreateInode = "INSERT INTO t_inodes VALUES(?,?,?,?,?,?,?,?,?,?,?)";

    /**
     * creates an entry in t_inodes table with initial values.
     * for optimization, initial value of reference count may be defined.
     * for newly created files , file size is zero. For directories 512.
     *
     * @param dbConnection
     * @param inode
     * @param uid
     * @param gid
     * @param mode
     * @param nlink
     * @throws java.sql.SQLException
     */
    public void createInode(Connection dbConnection, FsInode inode, int type, int uid, int gid, int mode, int nlink) throws SQLException {

        PreparedStatement stCreateInode = null;

        try {

            // default inode - nlink =1, size=0 ( 512 if directory), IO not allowed

            stCreateInode = dbConnection.prepareStatement(sqlCreateInode);

            Timestamp now = new Timestamp(System.currentTimeMillis());

            stCreateInode.setString(1, inode.toString());
            stCreateInode.setInt(2, type);
            stCreateInode.setInt(3, mode & UnixPermission.S_PERMS);
            stCreateInode.setInt(4, nlink);
            stCreateInode.setInt(5, uid);
            stCreateInode.setInt(6, gid);
            stCreateInode.setLong(7, (type == UnixPermission.S_IFDIR) ? 512 : 0);
            stCreateInode.setInt(8, _ioMode);
            stCreateInode.setTimestamp(9, now);
            stCreateInode.setTimestamp(10, now);
            stCreateInode.setTimestamp(11, now);

            stCreateInode.executeUpdate();

        } finally {
            SqlHelper.tryToClose(stCreateInode);
        }

    }

    /**
     * creates an entry in t_level_x table
     *
     * @param dbConnection
     * @param inode
     * @param uid
     * @param gid
     * @param mode
     * @param level
     * @return
     * @throws java.sql.SQLException
     */
    FsInode createLevel(Connection dbConnection, FsInode inode, int uid, int gid, int mode, int level) throws SQLException {

        PreparedStatement stCreateInodeLevel = null;

        try {

            Timestamp now = new Timestamp(System.currentTimeMillis());
            stCreateInodeLevel = dbConnection.prepareStatement("INSERT INTO t_level_" + level + " VALUES(?,?,1,?,?,0,?,?,?, NULL)");

            stCreateInodeLevel.setString(1, inode.toString());
            stCreateInodeLevel.setInt(2, mode);
            stCreateInodeLevel.setInt(3, uid);
            stCreateInodeLevel.setInt(4, gid);
            stCreateInodeLevel.setTimestamp(5, now);
            stCreateInodeLevel.setTimestamp(6, now);
            stCreateInodeLevel.setTimestamp(7, now);
            stCreateInodeLevel.executeUpdate();

        } finally {
            SqlHelper.tryToClose(stCreateInodeLevel);
        }

        return new FsInode(inode.getFs(), inode.toString(), level);
    }


    private static final String sqlRemoveInode = "DELETE FROM t_inodes WHERE ipnfsid=? AND inlink = 0";

    boolean removeInode(Connection dbConnection, FsInode inode) throws SQLException {
        int rc = 0;
        PreparedStatement stRemoveInode = null; //remove inode from t_inodes

        try {

            stRemoveInode = dbConnection.prepareStatement(sqlRemoveInode);

            stRemoveInode.setString(1, inode.toString());

            rc = stRemoveInode.executeUpdate();

        } finally {
            SqlHelper.tryToClose(stRemoveInode);
        }

        return rc > 0;
    }

    boolean removeInodeLevel(Connection dbConnection, FsInode inode, int level) throws ChimeraFsException, SQLException {

        int rc = 0;
        PreparedStatement stRemoveInodeLevel = null;
        try {

            stRemoveInodeLevel = dbConnection.prepareStatement("DELETE FROM t_level_" + level + " WHERE ipnfsid=?");
            stRemoveInodeLevel.setString(1, inode.toString());
            rc = stRemoveInodeLevel.executeUpdate();

        } finally {
            SqlHelper.tryToClose(stRemoveInodeLevel);
        }

        return rc > 0;

    }

    /**
     * increase inode reference count by 1;
     * the same as incNlink(dbConnection, inode, 1)
     *
     * @param dbConnection
     * @param inode
     * @throws java.sql.SQLException
     */
    void incNlink(Connection dbConnection, FsInode inode) throws SQLException {
        incNlink(dbConnection, inode, 1);
    }


    private static final String sqlIncNlink =
            "UPDATE t_inodes SET inlink=inlink +?,imtime=?,ictime=? WHERE ipnfsid=?";

    /**
     * increases the reference count of the inode by delta
     *
     * @param dbConnection
     * @param inode
     * @param delta
     * @throws java.sql.SQLException
     */
    void incNlink(Connection dbConnection, FsInode inode, int delta) throws SQLException {

        PreparedStatement stIncNlinkCount = null; // increase nlink count of the inode

        try {
            stIncNlinkCount = dbConnection.prepareStatement(sqlIncNlink);

            stIncNlinkCount.setInt(1, delta);
            stIncNlinkCount.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
            stIncNlinkCount.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            stIncNlinkCount.setString(4, inode.toString());

            stIncNlinkCount.executeUpdate();

        } finally {
            SqlHelper.tryToClose(stIncNlinkCount);
        }

    }

    /**
     * decreases inode reverence count by 1.
     * the same as decNlink(dbConnection, inode, 1)
     *
     * @param dbConnection
     * @param inode
     * @throws java.sql.SQLException
     */
    void decNlink(Connection dbConnection, FsInode inode) throws SQLException {
        decNlink(dbConnection, inode, 1);
    }


    private static final String sqlDecNlink =
            "UPDATE t_inodes SET inlink=inlink -?,imtime=?,ictime=? WHERE ipnfsid=?";

    /**
     * decreases inode reference count by delta
     *
     * @param dbConnection
     * @param inode
     * @param delta
     * @throws java.sql.SQLException
     */
    void decNlink(Connection dbConnection, FsInode inode, int delta) throws SQLException {

        PreparedStatement stDecNlinkCount = null; // decrease nlink count of the inode

        try {

            stDecNlinkCount = dbConnection.prepareStatement(sqlDecNlink);
            stDecNlinkCount.setInt(1, delta);
            stDecNlinkCount.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
            stDecNlinkCount.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            stDecNlinkCount.setString(4, inode.toString());

            stDecNlinkCount.executeUpdate();

        } finally {
            SqlHelper.tryToClose(stDecNlinkCount);
        }

    }


    private static final String sqlCreateEntryInParent = "INSERT INTO t_dirs VALUES(?,?,?)";

    /**
     * creates an entry name for the inode in the directory parent.
     * parent's reference count is not increased
     *
     * @param dbConnection
     * @param parent
     * @param name
     * @param inode
     * @throws java.sql.SQLException
     */
    void createEntryInParent(Connection dbConnection, FsInode parent, String name, FsInode inode) throws SQLException {

        PreparedStatement stInserIntoParent = null;
        try {

            stInserIntoParent = dbConnection.prepareStatement(sqlCreateEntryInParent);
            stInserIntoParent.setString(1, parent.toString());
            stInserIntoParent.setString(2, name);
            stInserIntoParent.setString(3, inode.toString());
            stInserIntoParent.executeUpdate();

        } finally {
            SqlHelper.tryToClose(stInserIntoParent);
        }

    }

    private static final String sqlRemoveEntryInParentByID = "DELETE FROM t_dirs WHERE ipnfsid=? AND iparent=?";

    void removeEntryInParentByID(Connection dbConnection, FsInode parent, FsInode inode) throws SQLException {

        PreparedStatement stRemoveFromParentById = null; // remove entry from parent
        try {

            stRemoveFromParentById = dbConnection.prepareStatement(sqlRemoveEntryInParentByID);
            stRemoveFromParentById.setString(1, inode.toString());
            stRemoveFromParentById.setString(2, parent.toString());

            stRemoveFromParentById.executeUpdate();

        } finally {
            SqlHelper.tryToClose(stRemoveFromParentById);
        }

    }

    private static final String sqlRemoveEntryInParentByName = "DELETE FROM t_dirs WHERE iname=? AND iparent=?";

    void removeEntryInParent(Connection dbConnection, FsInode parent, String name) throws SQLException {
        PreparedStatement stRemoveFromParentByName = null; // remove entry from parent
        try {

            stRemoveFromParentByName = dbConnection.prepareStatement(sqlRemoveEntryInParentByName);
            stRemoveFromParentByName.setString(1, name);
            stRemoveFromParentByName.setString(2, parent.toString());

            stRemoveFromParentByName.executeUpdate();

        } finally {
            SqlHelper.tryToClose(stRemoveFromParentByName);
        }

    }


    private static final String sqlGetParentOf = "SELECT iparent FROM t_dirs WHERE ipnfsid=? AND iname != '.' and iname != '..'";

    /**
     * return a parent of inode. In case of hard links, one of the parents is returned
     *
     * @param dbConnection
     * @param inode
     * @return
     * @throws java.sql.SQLException
     */
    FsInode getParentOf(Connection dbConnection, FsInode inode) throws SQLException {

        FsInode parent = null;
        ResultSet result = null;
        PreparedStatement stGetParentId = null;
        try {

            stGetParentId = dbConnection.prepareStatement(sqlGetParentOf);
            stGetParentId.setString(1, inode.toString());

            result = stGetParentId.executeQuery();

            if (result.next()) {
                parent = new FsInode(inode.getFs(), result.getString("iparent"));
            }

        } finally {
            SqlHelper.tryToClose(result);
            SqlHelper.tryToClose(stGetParentId);
        }

        return parent;
    }


    private static final String sqlGetParentOfDirectory = "SELECT iparent FROM t_dirs WHERE ipnfsid=? AND iname!='..' AND iname !='.'";

    /**
     * return a parent of inode. In case of hard links, one of the parents is returned
     *
     * @param dbConnection
     * @param inode
     * @return
     * @throws java.sql.SQLException
     */
    FsInode getParentOfDirectory(Connection dbConnection, FsInode inode) throws SQLException {

        FsInode parent = null;
        ResultSet result = null;
        PreparedStatement stGetParentId = null;
        try {

            stGetParentId = dbConnection.prepareStatement(sqlGetParentOfDirectory);
            stGetParentId.setString(1, inode.toString());

            result = stGetParentId.executeQuery();

            if (result.next()) {
                parent = new FsInode(inode.getFs(), result.getString("iparent"));
            }

        } finally {
            SqlHelper.tryToClose(result);
            SqlHelper.tryToClose(stGetParentId);
        }

        return parent;
    }

    private static final String sqlGetNameOf = "SELECT iname FROM t_dirs WHERE ipnfsid=? AND iparent=?";

    /**
     * return the the name of the inode in parent
     *
     * @param dbConnection
     * @param parent
     * @param inode
     * @return
     * @throws java.sql.SQLException
     */
    String getNameOf(Connection dbConnection, FsInode parent, FsInode inode) throws SQLException {

        ResultSet result = null;
        PreparedStatement stGetName = null;
        String name = null;
        try {

            stGetName = dbConnection.prepareStatement(sqlGetNameOf);
            stGetName.setString(1, inode.toString());
            stGetName.setString(2, parent.toString());

            result = stGetName.executeQuery();

            if (result.next()) {
                name = result.getString("iname");
            }

        } finally {
            SqlHelper.tryToClose(result);
            SqlHelper.tryToClose(stGetName);
        }

        return name;
    }

    private static final String sqlSetFileSize = "UPDATE t_inodes SET isize=?,imtime=?,ictime=? WHERE ipnfsid=?";

    void setFileSize(Connection dbConnection, FsInode inode, long newSize) throws SQLException {

        PreparedStatement ps = null;

        try {

            ps = dbConnection.prepareStatement(sqlSetFileSize);

            ps.setLong(1, newSize);
            ps.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
            ps.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            ps.setString(4, inode.toString());
            ps.executeUpdate();

        } finally {
            SqlHelper.tryToClose(ps);
        }
    }


    private static final String sqlSetFileOwner = "UPDATE t_inodes SET iuid=?,ictime=? WHERE ipnfsid=?";

    void setFileOwner(Connection dbConnection, FsInode inode, int level, int newOwner) throws SQLException {

        PreparedStatement ps = null;

        try {

            String fileSetModeQuery = null;

            if (level == 0) {
                fileSetModeQuery = sqlSetFileOwner;
            } else {
                fileSetModeQuery = "UPDATE t_level_" + level + " SET iuid=?,ictime=? WHERE ipnfsid=?";
            }
            ps = dbConnection.prepareStatement(fileSetModeQuery);

            ps.setInt(1, newOwner);
            ps.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
            ps.setString(3, inode.toString());
            ps.executeUpdate();

        } finally {
            SqlHelper.tryToClose(ps);
        }

    }


    private static final String sqlSetFileName = "UPDATE t_dirs SET iname=? WHERE iname=? AND iparent=?";

    void setFileName(Connection dbConnection, FsInode dir, String oldName, String newName) throws SQLException {

        PreparedStatement ps = null;

        try {

            FsInode destInode = inodeOf(dbConnection, dir, newName);
            FsInode srcInode = inodeOf(dbConnection, dir, oldName);

            if (destInode != null) {

                if (destInode.equals(srcInode)) {
                    // according to POSIX, we are done
                    return;
                }

                // remove old entry if exist
                removeEntryInParent(dbConnection, dir, newName);
                decNlink(dbConnection, destInode);
                decNlink(dbConnection, dir);
                removeInode(dbConnection, destInode);
            }

            ps = dbConnection.prepareStatement(sqlSetFileName);

            ps.setString(1, newName);
            ps.setString(2, oldName);
            ps.setString(3, dir.toString());
            ps.executeUpdate();

            // update parent modification time
            setFileMTime(dbConnection, dir, 0, System.currentTimeMillis());

        } finally {
            SqlHelper.tryToClose(ps);
        }
    }

    private static final String sqlSetInodeAttributes = "UPDATE t_inodes SET iatime=?, imtime=?, isize=?, iuid=?, igid=?, imode=?, itype=? WHERE ipnfsid=?";

    void setInodeAttributes(Connection dbConnection, FsInode inode, int level, Stat stat) throws SQLException {

        PreparedStatement ps = null;

        try {

            // attributes atime, mtime, size, uid, gid, mode

            /*
             *  only level 0 , e.g. original file allowed to have faked file size
             */
            if (level == 0) {

                ps = dbConnection.prepareStatement(sqlSetInodeAttributes);

                ps.setTimestamp(1, new Timestamp(stat.getATime()));
                ps.setTimestamp(2, new Timestamp(System.currentTimeMillis())); // even if client sends a new mtime, the real mtime is NOW
                ps.setLong(3, stat.getSize());
                ps.setInt(4, stat.getUid());
                ps.setInt(5, stat.getGid());
                ps.setInt(6, stat.getMode() & UnixPermission.S_PERMS);
                ps.setInt(7, stat.getMode() & UnixPermission.S_TYPE);
                ps.setString(8, inode.toString());
            } else {
                String fileSetModeQuery = "UPDATE t_level_" + level + " SET iatime=?, imtime=?, iuid=?, igid=?, imode=? WHERE ipnfsid=?";
                ps = dbConnection.prepareStatement(fileSetModeQuery);

                ps.setTimestamp(1, new Timestamp(stat.getATime()));
                ps.setTimestamp(2, new Timestamp(System.currentTimeMillis())); // even if client sends a new mtime, the real mtime is NOW
                ps.setInt(3, stat.getUid());
                ps.setInt(4, stat.getGid());
                ps.setInt(5, stat.getMode());
                ps.setString(6, inode.toString());
            }

            ps.executeUpdate();


        } finally {
            SqlHelper.tryToClose(ps);
        }
    }

    private static final String sqlSetFileATime = "UPDATE t_inodes SET iatime=? WHERE ipnfsid=?";

    void setFileATime(Connection dbConnection, FsInode inode, int level, long atime) throws SQLException {

        PreparedStatement ps = null;

        try {

            if (level == 0) {
                ps = dbConnection.prepareStatement(sqlSetFileATime);
            } else {
                String fileSetModeQuery = "UPDATE t_level_" + level + " SET iatime=? WHERE ipnfsid=?";
                ps = dbConnection.prepareStatement(fileSetModeQuery);
            }

            ps.setTimestamp(1, new Timestamp(atime));
            ps.setString(2, inode.toString());
            ps.executeUpdate();

        } finally {
            SqlHelper.tryToClose(ps);
        }
    }

    private static final String sqlSetFileCTime = "UPDATE t_inodes SET ictime=? WHERE ipnfsid=?";

    void setFileCTime(Connection dbConnection, FsInode inode, int level, long ctime) throws SQLException {

        PreparedStatement ps = null;

        try {


            if (level == 0) {
                ps = dbConnection.prepareStatement(sqlSetFileCTime);
            } else {
                String fileSetModeQuery = "UPDATE t_level_" + level + " SET ictime=? WHERE ipnfsid=?";
                ps = dbConnection.prepareStatement(fileSetModeQuery);
            }

            ps.setTimestamp(1, new Timestamp(ctime));
            ps.setString(2, inode.toString());
            ps.executeUpdate();

        } finally {
            SqlHelper.tryToClose(ps);
        }

    }

    private static final String sqlSetFileMTime = "UPDATE t_inodes SET imtime=? WHERE ipnfsid=?";

    void setFileMTime(Connection dbConnection, FsInode inode, int level, long mtime) throws SQLException {

        PreparedStatement ps = null;

        try {

            if (level == 0) {
                ps = dbConnection.prepareStatement(sqlSetFileMTime);
            } else {
                String fileSetModeQuery = "UPDATE t_level_" + level + " SET imtime=? WHERE ipnfsid=?";
                ps = dbConnection.prepareStatement(fileSetModeQuery);
            }
            ps.setTimestamp(1, new Timestamp(mtime));
            ps.setString(2, inode.toString());
            ps.executeUpdate();

        } finally {
            SqlHelper.tryToClose(ps);
        }

    }

    private static final String sqlSetFileGroup = "UPDATE t_inodes SET igid=?,ictime=? WHERE ipnfsid=?";

    void setFileGroup(Connection dbConnection, FsInode inode, int level, int newGroup) throws SQLException {

        PreparedStatement ps = null;
        try {

            if (level == 0) {
                ps = dbConnection.prepareStatement(sqlSetFileGroup);
            } else {
                String fileSetModeQuery = "UPDATE t_level_" + level + " SET igid=?,ictime=? WHERE ipnfsid=?";
                ps = dbConnection.prepareStatement(fileSetModeQuery);
            }
            ps.setInt(1, newGroup);
            ps.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
            ps.setString(3, inode.toString());
            ps.executeUpdate();


        } finally {
            SqlHelper.tryToClose(ps);
        }

    }

    private static final String sqlSetFileMode = "UPDATE t_inodes SET imode=?,ictime=? WHERE ipnfsid=?";

    void setFileMode(Connection dbConnection, FsInode inode, int level, int newMode) throws SQLException {

        PreparedStatement ps = null;
        try {

            if (level == 0) {
                ps = dbConnection.prepareStatement(sqlSetFileMode);
            } else {
                String fileSetModeQuery = "UPDATE t_level_" + level + " SET imode=?,ictime=? WHERE ipnfsid=?";
                ps = dbConnection.prepareStatement(fileSetModeQuery);
            }
            ps.setInt(1, newMode & UnixPermission.S_PERMS);
            ps.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
            ps.setString(3, inode.toString());
            ps.executeUpdate();


        } finally {
            SqlHelper.tryToClose(ps);
        }
    }

    private static final String sqlIsIoEnabled = "SELECT iio FROM t_inodes WHERE ipnfsid=?";

    /**
     * checks for IO flag of the inode. if IO enabled, regular read and write operations are allowed
     *
     * @param dbConnection
     * @param inode
     * @return
     * @throws java.sql.SQLException
     */
    boolean isIoEnabled(Connection dbConnection, FsInode inode) throws SQLException {

        boolean ioEnabled = false;
        ResultSet rs = null;
        PreparedStatement stIsIoEnabled = null;

        try {
            stIsIoEnabled = dbConnection.prepareStatement(sqlIsIoEnabled);
            stIsIoEnabled.setString(1, inode.toString());

            rs = stIsIoEnabled.executeQuery();
            if (rs.next()) {
                ioEnabled = rs.getInt("iio") == 1;
            }

        } finally {
            SqlHelper.tryToClose(rs);
            SqlHelper.tryToClose(stIsIoEnabled);
        }
        return ioEnabled;

    }

    private static final String sqlSetInodeIo = "UPDATE t_inodes SET iio=? WHERE ipnfsid=?";

    void setInodeIo(Connection dbConnection, FsInode inode, boolean enable) throws SQLException {

        PreparedStatement ps = null;

        try {

            ps = dbConnection.prepareStatement(sqlSetInodeIo);
            ps.setInt(1, enable ? 1 : 0);
            ps.setString(2, inode.toString());

            ps.executeUpdate();

        } finally {
            SqlHelper.tryToClose(ps);
        }
    }

    int write(Connection dbConnection, FsInode inode, int level, long beginIndex, byte[] data, int offset, int len) throws SQLException, IOException {

        PreparedStatement ps = null;
        ResultSet rs = null;

        try {

            if (level == 0) {

                ps = dbConnection.prepareStatement("SELECT ipnfsid FROM t_inodes_data WHERE ipnfsid=?");
                ps.setString(1, inode.toString());

                rs = ps.executeQuery();
                boolean exist = rs.next();
                SqlHelper.tryToClose(rs);
                SqlHelper.tryToClose(ps);

                if (exist) {
                    // entry exist, update only
                    // read old data upto beginIndex
                    ps = dbConnection.prepareStatement("SELECT ifiledata FROM t_inodes_data WHERE ipnfsid=?");
                    ps.setString(1, inode.toString());
                    rs = ps.executeQuery();
                    if (rs.next()) {
                        InputStream in = rs.getBinaryStream(1);

                        String writeStream = "UPDATE t_inodes_data SET ifiledata=? WHERE ipnfsid=?";

                        ps = dbConnection.prepareStatement(writeStream);

                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        int next = in.read();
                        long curr = 0;
                        while (next > -1 && curr != beginIndex) {
                            bos.write(next);
                            next = in.read();
                            curr++;
                        }
                        bos.flush();
                        byte[] currentBytes = bos.toByteArray();


                        byte newBytes[] = new byte[currentBytes.length + data.length];
                        System.arraycopy(currentBytes, 0, newBytes, 0, currentBytes.length);
                        System.arraycopy(data, 0, newBytes, currentBytes.length, data.length);

                        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(newBytes);

                        ps.setBinaryStream(1, byteArrayInputStream, newBytes.length);
                        ps.setString(2, inode.toString());

                        ps.executeUpdate();
                        SqlHelper.tryToClose(ps);
                    }

                } else {
                    // new entry
                    String writeStream = "INSERT INTO t_inodes_data VALUES (?,?)";

                    ps = dbConnection.prepareStatement(writeStream);

                    ps.setString(1, inode.toString());
                    ps.setBinaryStream(2, new ByteArrayInputStream(data, offset, len), len);

                    ps.executeUpdate();
                    SqlHelper.tryToClose(ps);

                }

                // correct file size
                String writeStream = "UPDATE t_inodes SET isize=? WHERE ipnfsid=?";

                ps = dbConnection.prepareStatement(writeStream);

                ps.setLong(1, beginIndex+len);
                ps.setString(2, inode.toString());

                ps.executeUpdate();

            } else {

                // if level does not exist, create it

                if (stat(dbConnection, inode, level) == null) {
                    createLevel(dbConnection, inode, 0, 0, 644, level);
                }

                String writeStream = "UPDATE t_level_" + level + " SET ifiledata=?,isize=? WHERE ipnfsid=?";
                ps = dbConnection.prepareStatement(writeStream);

                ps.setBinaryStream(1, new ByteArrayInputStream(data, offset, len), len);
                ps.setLong(2, len);
                ps.setString(3, inode.toString());

                ps.executeUpdate();
            }


        } finally {
            SqlHelper.tryToClose(rs);
            SqlHelper.tryToClose(ps);
        }

        return len;
    }

    int read(Connection dbConnection, FsInode inode, int level, long beginIndex, byte[] data, int offset, int len) throws SQLException, IOException {

        int count = 0;
        PreparedStatement stReadFromInode = null;
        ResultSet rs = null;

        try {

            if (level == 0) {
                stReadFromInode = dbConnection.prepareStatement("SELECT ifiledata FROM t_inodes_data WHERE ipnfsid=?");
            } else {
                stReadFromInode = dbConnection.prepareStatement("SELECT ifiledata FROM t_level_" + level + " WHERE ipnfsid=?");
            }

            stReadFromInode.setString(1, inode.toString());
            rs = stReadFromInode.executeQuery();

            if (rs.next()) {
                InputStream in = rs.getBinaryStream(1);

                in.skip(beginIndex);
                int c;
                while (((c = in.read()) != -1) && (count < len)) {
                    data[offset + count] = (byte) c;
                    ++count;
                }
                //count = in.available() > len ? len : in.available() ;
                //in.read(data, offset, count);
            }

        } finally {
            SqlHelper.tryToClose(rs);
            SqlHelper.tryToClose(stReadFromInode);
        }

        return count;
    }


    /////////////////////////////////////////////////////////////////////
    ////
    ////   Location info
    ////
    ////////////////////////////////////////////////////////////////////

    private static final String sqlGetInodeLocations = "SELECT ilocation,ipriority,ictime,iatime  " + "FROM t_locationinfo WHERE itype=? AND ipnfsid=? AND istate=1 ORDER BY ipriority DESC";

    /**
     * returns a list of locations of defined type for the inode.
     * only 'online' locations is returned
     *
     * @param dbConnection
     * @param inode
     * @param type
     * @return
     * @throws ChimeraFsException
     * @throws java.sql.SQLException
     */
    List<StorageLocatable> getInodeLocations(Connection dbConnection, FsInode inode, int type) throws ChimeraFsException, SQLException {

        List<StorageLocatable> locations = new ArrayList<StorageLocatable>();
        ResultSet rs = null;
        PreparedStatement stGetInodeLocations = null;
        try {

            stGetInodeLocations = dbConnection.prepareStatement(sqlGetInodeLocations);

            stGetInodeLocations.setInt(1, type);
            stGetInodeLocations.setString(2, inode.toString());

            rs = stGetInodeLocations.executeQuery();

            while (rs.next()) {

                long ctime = rs.getTimestamp("ictime").getTime();
                long atime = rs.getTimestamp("iatime").getTime();
                int priority = rs.getInt("ipriority");
                String location = rs.getString("ilocation");

                StorageLocatable inodeLocation = new StorageGenericLocation(type, priority, location, ctime, atime, true);
                locations.add(inodeLocation);
            }

        } finally {
            SqlHelper.tryToClose(rs);
            SqlHelper.tryToClose(stGetInodeLocations);
        }

        return locations;
    }

    private static final String sqlAddInodeLocation = "INSERT INTO t_locationinfo VALUES(?,?,?,?,?,?,?)";

    /**
     * adds a new location for the inode
     *
     * @param dbConnection
     * @param inode
     * @param type
     * @param location
     * @throws ChimeraFsException
     * @throws java.sql.SQLException
     */
    void addInodeLocation(Connection dbConnection, FsInode inode, int type, String location) throws ChimeraFsException, SQLException {
        PreparedStatement stAddInodeLocation = null; // add a new  location in the storage system for the inode
        try {

            stAddInodeLocation = dbConnection.prepareStatement(sqlAddInodeLocation);

            Timestamp now = new Timestamp(System.currentTimeMillis());
            stAddInodeLocation.setString(1, inode.toString());
            stAddInodeLocation.setInt(2, type);
            stAddInodeLocation.setString(3, location);
            stAddInodeLocation.setInt(4, 10); // default priority
            stAddInodeLocation.setTimestamp(5, now);
            stAddInodeLocation.setTimestamp(6, now);
            stAddInodeLocation.setInt(7, 1); // online

            stAddInodeLocation.executeUpdate();

        } finally {
            SqlHelper.tryToClose(stAddInodeLocation);
        }
    }


    private static final String sqlClearInodeLocation = "DELETE FROM t_locationinfo WHERE ipnfsid=? AND itype=? AND ilocation=?";

    /**
     * remove the location for a inode
     *
     * @param dbConnection
     * @param inode
     * @param type
     * @param location
     * @throws ChimeraFsException
     * @throws java.sql.SQLException
     */
    void clearInodeLocation(Connection dbConnection, FsInode inode, int type, String location) throws ChimeraFsException, SQLException {
        PreparedStatement stClearInodeLocation = null; // clear a location in the storage system for the inode

        try {
            stClearInodeLocation = dbConnection.prepareStatement(sqlClearInodeLocation);
            stClearInodeLocation.setString(1, inode.toString());
            stClearInodeLocation.setInt(2, type);
            stClearInodeLocation.setString(3, location);

            stClearInodeLocation.executeUpdate();

        } finally {
            SqlHelper.tryToClose(stClearInodeLocation);
        }
    }


    private static final String sqlClearInodeLocations = "DELETE FROM t_locationinfo WHERE ipnfsid=?";

    /**
     * remove all locations for a inode
     *
     * @param dbConnection
     * @param inode
     * @throws ChimeraFsException
     * @throws java.sql.SQLException
     */
    void clearInodeLocations(Connection dbConnection, FsInode inode) throws ChimeraFsException, SQLException {
        PreparedStatement stClearInodeLocations = null; // clear a location in the storage system for the inode

        try {
            stClearInodeLocations = dbConnection.prepareStatement(sqlClearInodeLocations);
            stClearInodeLocations.setString(1, inode.toString());

            stClearInodeLocations.executeUpdate();

        } finally {
            SqlHelper.tryToClose(stClearInodeLocations);
        }
    }


    /////////////////////////////////////////////////////////////////////
    ////
    ////   Directory tags handling
    ////
    ////////////////////////////////////////////////////////////////////


    private static final String sqlTags = "SELECT itagname FROM t_tags where ipnfsid=?";

    String[] tags(Connection dbConnection, FsInode inode) throws SQLException {

        String[] list = null;
        ResultSet rs = null;
        PreparedStatement stGetTags = null;
        try {

            stGetTags = dbConnection.prepareStatement(sqlTags);
            stGetTags.setString(1, inode.toString());
            rs = stGetTags.executeQuery();

            List<String> v = new ArrayList<String>();

            while (rs.next()) {
                v.add(rs.getString("itagname"));
            }
            rs.close();

            list = v.toArray(new String[v.size()]);

        } finally {
            SqlHelper.tryToClose(rs);
            SqlHelper.tryToClose(stGetTags);
        }

        return list;
    }

    /**
     * creates a new tag for the inode.
     * the inode becomes the tag origin.
     *
     * @param dbConnection
     * @param inode
     * @param name
     * @param uid
     * @param gid
     * @param mode
     * @throws java.sql.SQLException
     */
    void createTag(Connection dbConnection, FsInode inode, String name, int uid, int gid, int mode) throws SQLException {

        String id = createTagInode(dbConnection, uid, gid, mode);
        assignTagToDir(dbConnection, id, name, inode, false, true);
    }


    private static final String sqlGetTagId = "SELECT itagid FROM t_tags WHERE ipnfsid=? AND itagname=?";

    /**
     * returns tag id of a tag associated with inode
     *
     * @param dbConnection
     * @param dir
     * @param tag
     * @return
     * @throws java.sql.SQLException
     */
    String getTagId(Connection dbConnection, FsInode dir, String tag) throws SQLException {
        String tagId = null;
        ResultSet rs = null;
        PreparedStatement stGetTagId = null;

        try {
            stGetTagId = dbConnection.prepareStatement(sqlGetTagId);

            stGetTagId.setString(1, dir.toString());
            stGetTagId.setString(2, tag);

            rs = stGetTagId.executeQuery();
            if (rs.next()) {
                tagId = rs.getString("itagid");
            }

        } finally {
            SqlHelper.tryToClose(rs);
            SqlHelper.tryToClose(stGetTagId);
        }
        return tagId;
    }


    private static final String sqlCreateTagInode = "INSERT INTO t_tags_inodes VALUES(?,?,1,?,?,0,?,?,?,NULL)";

    /**
     * creates a new id for a tag and sores it into t_tags_inodes table.
     *
     * @param dbConnection
     * @param uid
     * @param gid
     * @param mode
     * @return
     * @throws java.sql.SQLException
     */
    String createTagInode(Connection dbConnection, int uid, int gid, int mode) throws SQLException {

        String id = UUID.randomUUID().toString().toUpperCase();
        PreparedStatement stCreateTagInode = null;
        try {

            stCreateTagInode = dbConnection.prepareStatement(sqlCreateTagInode);

            Timestamp now = new Timestamp(System.currentTimeMillis());

            stCreateTagInode.setString(1, id);
            stCreateTagInode.setInt(2, mode | UnixPermission.S_IFREG);
            stCreateTagInode.setInt(3, uid);
            stCreateTagInode.setInt(4, gid);
            stCreateTagInode.setTimestamp(5, now);
            stCreateTagInode.setTimestamp(6, now);
            stCreateTagInode.setTimestamp(7, now);

            stCreateTagInode.executeUpdate();

        } finally {
            SqlHelper.tryToClose(stCreateTagInode);
        }
        return id;
    }


    private static final String sqlAssignTagToDir_update = "UPDATE t_tags SET itagid=?,isorign=? WHERE ipnfsid=? AND itagname=?";
    private static final String sqlAssignTagToDir_add = "INSERT INTO t_tags VALUES(?,?,?,1)";

    /**
     * creates a new or update existing tag for a directory
     *
     * @param dbConnection
     * @param tagId
     * @param tagName
     * @param dir
     * @param isUpdate
     * @param isOrign
     * @throws java.sql.SQLException
     */
    void assignTagToDir(Connection dbConnection, String tagId, String tagName, FsInode dir, boolean isUpdate, boolean isOrign) throws SQLException {

        PreparedStatement ps = null;
        try {

            if (isUpdate) {
                ps = dbConnection.prepareStatement(sqlAssignTagToDir_update);

                ps.setString(1, tagId);
                ps.setInt(2, isOrign ? 1 : 0);
                ps.setString(3, dir.toString());
                ps.setString(4, tagName);

            } else {
                ps = dbConnection.prepareStatement(sqlAssignTagToDir_add);

                ps.setString(1, dir.toString());
                ps.setString(2, tagName);
                ps.setString(3, tagId);
            }

            ps.executeUpdate();

        } finally {
            SqlHelper.tryToClose(ps);
        }
    }

    private static final String sqlSetTag = "UPDATE t_tags_inodes SET ivalue=?, isize=? WHERE itagid=?";

    int setTag(Connection dbConnection, FsInode inode, String tagName, byte[] data, int offset, int len) throws SQLException, ChimeraFsException {

        PreparedStatement stSetTag = null;
        try {

            String tagId = getTagId(dbConnection, inode, tagName);

            if (!isTagOwner(dbConnection, inode, tagName)) {
                // tag bunching
                Stat tagStat = statTag(dbConnection, inode, tagName);

                tagId = createTagInode(dbConnection, tagStat.getUid(), tagStat.getGid(), tagStat.getMode());
                assignTagToDir(dbConnection, tagId, tagName, inode, true, true);

            }

            stSetTag = dbConnection.prepareStatement(sqlSetTag);
            stSetTag.setBinaryStream(1, new ByteArrayInputStream(data, offset, len), len);
            stSetTag.setLong(2, len);
            stSetTag.setString(3, tagId);
            stSetTag.executeUpdate();

        } finally {
            SqlHelper.tryToClose(stSetTag);
        }

        return len;

    }

    private static final String sqlRemoveTag = "DELETE FROM t_tags WHERE ipnfsid=?";

    void removeTag(Connection dbConnection, FsInode dir) throws SQLException {

        PreparedStatement ps = null;
        try {

            ps = dbConnection.prepareStatement(sqlRemoveTag);
            ps.setString(1, dir.toString());

            ps.executeUpdate();

        } finally {
            SqlHelper.tryToClose(ps);
        }
    }


    private static final String sqlGetTag = "SELECT ivalue,isize FROM t_tags_inodes WHERE itagid=?";

    /**
     * get content of the tag associated with name for inode
     *
     * @param dbConnection
     * @param inode
     * @param tagName
     * @param data
     * @param offset
     * @param len
     * @return
     * @throws java.sql.SQLException
     * @throws java.io.IOException
     */
    int getTag(Connection dbConnection, FsInode inode, String tagName, byte[] data, int offset, int len) throws SQLException, IOException {

        int count = 0;
        ResultSet rs = null;
        PreparedStatement stGetTag = null;
        try {

            String tagId = getTagId(dbConnection, inode, tagName);

            stGetTag = dbConnection.prepareStatement(sqlGetTag);
            stGetTag.setString(1, tagId);
            rs = stGetTag.executeQuery();

            if (rs.next()) {

                InputStream in = rs.getBinaryStream("ivalue");
                /*
                 * some databases (hsqldb in particular) fill a full record for
                 * BLOBs and on read reads a full record, which is not what we expect.
                 *
                 */
                int size = Math.min(len, (int) rs.getLong("isize"));

                while (count < size) {

                    int c = in.read();
                    if (c == -1) break;

                    data[offset + count] = (byte) c;
                    ++count;
                }
                in.close();
            }

        } finally {
            SqlHelper.tryToClose(rs);
            SqlHelper.tryToClose(stGetTag);
        }

        return count;
    }

    private static final String sqlStatTag = "SELECT isize,inlink,imode,iuid,igid,iatime,ictime,imtime FROM t_tags_inodes WHERE itagid=?";

    Stat statTag(Connection dbConnection, FsInode dir, String name) throws ChimeraFsException, SQLException {


        org.dcache.chimera.posix.Stat ret = new org.dcache.chimera.posix.Stat();
        PreparedStatement stStatTag = null; // get tag attributes
        try {

            String tagId = getTagId(dbConnection, dir, name);

            if (tagId == null) {
                throw new FileNotFoundHimeraFsException("tag do not exist");
            }

            stStatTag = dbConnection.prepareStatement(sqlStatTag);
            stStatTag.setString(1, tagId);
            ResultSet statResult = stStatTag.executeQuery();

            if (statResult.next()) {

                ret.setSize(statResult.getLong("isize"));
                ret.setATime(statResult.getTimestamp("iatime").getTime());
                ret.setCTime(statResult.getTimestamp("ictime").getTime());
                ret.setMTime(statResult.getTimestamp("imtime").getTime());
                ret.setUid(statResult.getInt("iuid"));
                ret.setGid(statResult.getInt("igid"));
                ret.setMode(statResult.getInt("imode"));
                ret.setNlink(statResult.getInt("inlink"));
                ret.setIno((int) dir.id());
                ret.setDev(17);

            } else {
                // file not found
                throw new FileNotFoundHimeraFsException(name);
            }

        } finally {
            SqlHelper.tryToClose(stStatTag);
        }

        return ret;
    }


    private static final String sqlIsTagOwner = "SELECT isorign FROM t_tags WHERE ipnfsid=? AND itagname=?";

    /**
     * checks for tag ownership
     *
     * @param dbConnection
     * @param dir
     * @param tagName
     * @return true, if inode is the origin of the tag
     * @throws java.sql.SQLException
     */
    boolean isTagOwner(Connection dbConnection, FsInode dir, String tagName) throws SQLException {

        boolean isOwner = false;
        PreparedStatement stTagOwner = null;
        ResultSet rs = null;

        try {

            stTagOwner = dbConnection.prepareStatement(sqlIsTagOwner);
            stTagOwner.setString(1, dir.toString());
            stTagOwner.setString(2, tagName);

            rs = stTagOwner.executeQuery();
            if (rs.next()) {
                int rc = rs.getInt("isorign");
                if (rc == 1) {
                    isOwner = true;
                }
            }

        } finally {
            SqlHelper.tryToClose(rs);
            SqlHelper.tryToClose(stTagOwner);
        }

        return isOwner;
    }


    private final static String sqlCopyTag = "INSERT INTO t_tags ( SELECT ?, itagname, itagid, 0 from t_tags WHERE ipnfsid=?)";

    /**
     * copy all directory tags from origin directory to destination. New copy marked as inherited.
     *
     * @param dbConnection
     * @param orign
     * @param destination
     * @throws java.sql.SQLException
     */
    void copyTags(Connection dbConnection, FsInode orign, FsInode destination) throws SQLException {

        PreparedStatement stCopyTags = null;
        try {

            stCopyTags = dbConnection.prepareStatement(sqlCopyTag);
            stCopyTags.setString(1, destination.toString());
            stCopyTags.setString(2, orign.toString());
            stCopyTags.executeUpdate();

        } finally {
            SqlHelper.tryToClose(stCopyTags);
        }

    }

    /*
     * Storage Information
     *
     * Currently it's not allowed to modify it
     */


    private static final String sqlSetStorageInfo = "INSERT INTO t_storageinfo VALUES(?,?,?,?)";

    /**
     * set storage info of inode in t_storageinfo table.
     * once storage info is stores, it's not allowed to modify it
     *
     * @param dbConnection
     * @param inode
     * @param storageInfo
     * @throws ChimeraFsException
     * @throws java.sql.SQLException
     */
    void setStorageInfo(Connection dbConnection, FsInode inode, InodeStorageInformation storageInfo) throws ChimeraFsException, SQLException {

        PreparedStatement stSetStorageInfo = null; // clear locations in the storage system for the inode

        try {

            // no records updated - insert a new one

            stSetStorageInfo = dbConnection.prepareStatement(sqlSetStorageInfo);
            stSetStorageInfo.setString(1, inode.toString());
            stSetStorageInfo.setString(2, storageInfo.hsmName());
            stSetStorageInfo.setString(3, storageInfo.storageGroup());
            stSetStorageInfo.setString(4, storageInfo.storageSubGroup());

            stSetStorageInfo.executeUpdate();

        } finally {
            SqlHelper.tryToClose(stSetStorageInfo);
        }
    }

    private static final String sqlGetAccessLatency = "SELECT iaccessLatency FROM t_access_latency WHERE ipnfsid=?";

    /**
     * @param dbConnection
     * @param inode
     * @return Access Latency or null if not defined
     * @throws ChimeraFsException
     * @throws SQLException
     */
    AccessLatency getAccessLatency(Connection dbConnection, FsInode inode) throws ChimeraFsException, SQLException {
        AccessLatency accessLatency = null;
        PreparedStatement stGetAccessLatency = null;
        ResultSet alResultSet = null;

        try {

            stGetAccessLatency = dbConnection.prepareStatement(sqlGetAccessLatency);
            stGetAccessLatency.setString(1, inode.toString());

            alResultSet = stGetAccessLatency.executeQuery();
            if (alResultSet.next()) {
                accessLatency = AccessLatency.valueOf(alResultSet.getInt("iaccessLatency"));
            }


        } finally {
            SqlHelper.tryToClose(alResultSet);
            SqlHelper.tryToClose(stGetAccessLatency);
        }

        return accessLatency;
    }


    private static final String sqlGetRetentionPolicy = "SELECT iretentionPolicy FROM t_retention_policy WHERE ipnfsid=?";

    /**
     * @param dbConnection
     * @param inode
     * @return Retention Policy or null if not defined
     * @throws ChimeraFsException
     * @throws SQLException
     */
    RetentionPolicy getRetentionPolicy(Connection dbConnection, FsInode inode) throws ChimeraFsException, SQLException {
        RetentionPolicy retentionPolicy = null;
        PreparedStatement stRetentionPolicy = null;
        ResultSet rpResultSet = null;

        try {

            stRetentionPolicy = dbConnection.prepareStatement(sqlGetRetentionPolicy);
            stRetentionPolicy.setString(1, inode.toString());

            rpResultSet = stRetentionPolicy.executeQuery();
            if (rpResultSet.next()) {
                retentionPolicy = RetentionPolicy.valueOf(rpResultSet.getInt("iretentionPolicy"));
            }


        } finally {
            SqlHelper.tryToClose(rpResultSet);
            SqlHelper.tryToClose(stRetentionPolicy);
        }

        return retentionPolicy;
    }

    private static final String sqlSetAccessLatency = "INSERT INTO t_access_latency VALUES(?,?)";
    private static final String sqlUpdateAccessLatency = "UPDATE t_access_latency SET iaccessLatency=? WHERE ipnfsid=?";

    void setAccessLatency(Connection dbConnection, FsInode inode, AccessLatency accessLatency) throws ChimeraFsException, SQLException {

        PreparedStatement stSetAccessLatency = null; // clear locations in the storage system for the inode
        PreparedStatement stUpdateAccessLatency = null;
        try {

            stUpdateAccessLatency = dbConnection.prepareStatement(sqlUpdateAccessLatency);
            stUpdateAccessLatency.setInt(1, accessLatency.getId());
            stUpdateAccessLatency.setString(2, inode.toString());

            if (stUpdateAccessLatency.executeUpdate() == 0) {

                // no records updated - insert a new one

                stSetAccessLatency = dbConnection.prepareStatement(sqlSetAccessLatency);
                stSetAccessLatency.setString(1, inode.toString());
                stSetAccessLatency.setInt(2, accessLatency.getId());

                stSetAccessLatency.executeUpdate();
            }

        } finally {
            SqlHelper.tryToClose(stSetAccessLatency);
            SqlHelper.tryToClose(stUpdateAccessLatency);
        }
    }

    private static final String sqlSetRetentionPolicy = "INSERT INTO t_retention_policy VALUES(?,?)";
    private static final String sqlUpdateRetentionPolicy = "UPDATE t_retention_policy SET iretentionPolicy=? WHERE ipnfsid=?";

    void setRetentionPolicy(Connection dbConnection, FsInode inode, RetentionPolicy accessLatency) throws ChimeraFsException, SQLException {

        PreparedStatement stSetRetentionPolicy = null; // clear locations in the storage system for the inode
        PreparedStatement stUpdateRetentionPolicy = null;
        try {

            stUpdateRetentionPolicy = dbConnection.prepareStatement(sqlUpdateRetentionPolicy);
            stUpdateRetentionPolicy.setInt(1, accessLatency.getId());
            stUpdateRetentionPolicy.setString(2, inode.toString());

            if (stUpdateRetentionPolicy.executeUpdate() == 0) {

                // no records updated - insert a new one

                stSetRetentionPolicy = dbConnection.prepareStatement(sqlSetRetentionPolicy);
                stSetRetentionPolicy.setString(1, inode.toString());
                stSetRetentionPolicy.setInt(2, accessLatency.getId());

                stSetRetentionPolicy.executeUpdate();
            }

        } finally {
            SqlHelper.tryToClose(stSetRetentionPolicy);
            SqlHelper.tryToClose(stUpdateRetentionPolicy);
        }
    }

    private static final String sqlRemoveStorageInfo = "DELETE FROM t_storageinfo WHERE ipnfsid=?";

    void removeStorageInfo(Connection dbConnection, FsInode inode) throws ChimeraFsException, SQLException {

        PreparedStatement stRemoveStorageInfo = null; // clear locations in the storage system for the inode
        try {
            stRemoveStorageInfo = dbConnection.prepareStatement(sqlRemoveStorageInfo);
            stRemoveStorageInfo.setString(1, inode.toString());
            stRemoveStorageInfo.executeUpdate();

        } finally {
            SqlHelper.tryToClose(stRemoveStorageInfo);
        }
    }


    private static final String sqlGetStorageInfo = "SELECT ihsmName, istorageGroup, istorageSubGroup, iaccessLatency, iretentionPolicy FROM " + "t_storageinfo,t_access_latency,t_retention_policy " + "WHERE t_storageinfo.ipnfsid=t_access_latency.ipnfsid " + "AND t_storageinfo.ipnfsid=t_retention_policy.ipnfsid AND t_storageinfo.ipnfsid=?";

    /**
     * returns storage information like storage group, storage sub group, hsm,
     * retention policy and access latency associated with the inode.
     *
     * @param dbConnection
     * @param inode
     * @return
     * @throws ChimeraFsException
     * @throws java.sql.SQLException
     */
    InodeStorageInformation getSorageInfo(Connection dbConnection, FsInode inode) throws ChimeraFsException, SQLException {

        InodeStorageInformation storageInfo = null;

        ResultSet storageInfoResult = null;
        PreparedStatement stGetStorageInfo = null; // clear locations in the storage system for the inode
        try {

            stGetStorageInfo = dbConnection.prepareStatement(sqlGetStorageInfo);
            stGetStorageInfo.setString(1, inode.toString());
            storageInfoResult = stGetStorageInfo.executeQuery();

            if (storageInfoResult.next()) {

                String hsmName = storageInfoResult.getString("ihsmName");
                String storageGroup = storageInfoResult.getString("istoragegroup");
                String storageSubGroup = storageInfoResult.getString("istoragesubgroup");
                AccessLatency accessLatency = AccessLatency.valueOf(storageInfoResult.getInt("iaccessLatency"));
                RetentionPolicy retentionPolicy = RetentionPolicy.valueOf(storageInfoResult.getInt("iretentionPolicy"));

                storageInfo = new InodeStorageInformation(inode, hsmName, storageGroup, storageSubGroup, accessLatency, retentionPolicy);
            } else {
                // file not found
                throw new FileNotFoundHimeraFsException(inode.toString());
            }

        } finally {
            SqlHelper.tryToClose(storageInfoResult);
            SqlHelper.tryToClose(stGetStorageInfo);
        }

        return storageInfo;
    }


    /*
    * directory caching
    * the following set of methods should help to path2inode and inode2path operations
    */

    private static final String sqlGetInodeFromCache = "SELECT ipnfsid FROM t_dir_cache WHERE ipath=?";

    String getInodeFromCache(Connection dbConnection, String path) throws SQLException {

        String inodeString = null;
        PreparedStatement stGetInodeFromCache = null;
        ResultSet getInodeFromCacheResultSet = null;

        try {
            stGetInodeFromCache = dbConnection.prepareStatement(sqlGetInodeFromCache);

            stGetInodeFromCache.setString(1, path);

            getInodeFromCacheResultSet = stGetInodeFromCache.executeQuery();
            if (getInodeFromCacheResultSet.next()) {
                inodeString = getInodeFromCacheResultSet.getString("ipnfsid");
            }

        } finally {
            SqlHelper.tryToClose(getInodeFromCacheResultSet);
            SqlHelper.tryToClose(stGetInodeFromCache);
        }


        return inodeString;

    }

    private static final String sqlGetPathFromCache = "SELECT ipath FROM t_dir_cache WHERE ipnfsid=?";

    String getPathFromCache(Connection dbConnection, FsInode inode) throws SQLException {

        String path = null;
        PreparedStatement stGetPathFromCache = null;
        ResultSet getPathFromCacheResultSet = null;

        try {
            stGetPathFromCache = dbConnection.prepareStatement(sqlGetPathFromCache);

            stGetPathFromCache.setString(1, inode.toString());

            getPathFromCacheResultSet = stGetPathFromCache.executeQuery();
            if (getPathFromCacheResultSet.next()) {
                path = getPathFromCacheResultSet.getString("ipath");
            }

        } finally {
            SqlHelper.tryToClose(getPathFromCacheResultSet);
            SqlHelper.tryToClose(stGetPathFromCache);
        }

        return path;

    }

    private static final String sqlSetInodeChecksum = "INSERT INTO t_inodes_checksum VALUES(?,?,?)";

    /**
     * add a checksum value of <i>type</i> to an inode
     *
     * @param dbConnection
     * @param inode
     * @param type
     * @param value
     * @throws SQLException
     */
    void setInodeChecksum(Connection dbConnection, FsInode inode, int type, String value) throws SQLException {

        PreparedStatement stSetInodeChecksum = null;

        try {

            stSetInodeChecksum = dbConnection.prepareStatement(sqlSetInodeChecksum);
            stSetInodeChecksum.setString(1, inode.toString());
            stSetInodeChecksum.setInt(2, type);
            stSetInodeChecksum.setString(3, value);

            stSetInodeChecksum.executeUpdate();

        } finally {
            SqlHelper.tryToClose(stSetInodeChecksum);
        }

    }


    private static final String sqlGetInodeChecksum = "SELECT isum FROM t_inodes_checksum WHERE ipnfsid=? AND itype=?";

    /**
     * @param dbConnection
     * @param inode
     * @param type
     * @return HEX presentation of the checksum value for the specific file and checksum type or null
     * @throws SQLException
     */
    String getInodeChecksum(Connection dbConnection, FsInode inode, int type) throws SQLException {


        String checksum = null;

        PreparedStatement stGetInodeChecksum = null;
        ResultSet getGetInodeChecksumResultSet = null;

        try {

            stGetInodeChecksum = dbConnection.prepareStatement(sqlGetInodeChecksum);
            stGetInodeChecksum.setString(1, inode.toString());
            stGetInodeChecksum.setInt(2, type);

            getGetInodeChecksumResultSet = stGetInodeChecksum.executeQuery();

            if (getGetInodeChecksumResultSet.next()) {
                checksum = getGetInodeChecksumResultSet.getString("isum");
            }

        } finally {
            SqlHelper.tryToClose(getGetInodeChecksumResultSet);
            SqlHelper.tryToClose(stGetInodeChecksum);
        }

        return checksum;

    }

    private static final String sqlRemoveInodeChecksum = "DELETE FROM t_inodes_checksum WHERE ipnfsid=? AND itype=?";
    private static final String sqlRemoveInodeAllChecksum = "DELETE FROM t_inodes_checksum WHERE ipnfsid=?";

    /**
     * @param dbConnection
     * @param inode
     * @param type
     * @throws SQLException
     */
    void removeInodeChecksum(Connection dbConnection, FsInode inode, int type) throws SQLException {

        PreparedStatement stRemoveInodeChecksum = null;

        try {

            if (type >= 0) {
                stRemoveInodeChecksum = dbConnection.prepareStatement(sqlRemoveInodeChecksum);
                stRemoveInodeChecksum.setInt(2, type);
            } else {
                stRemoveInodeChecksum = dbConnection.prepareStatement(sqlRemoveInodeAllChecksum);
            }

            stRemoveInodeChecksum.setString(1, inode.toString());

            stRemoveInodeChecksum.executeUpdate();


        } finally {
            SqlHelper.tryToClose(stRemoveInodeChecksum);
        }

    }


    /**
     * get inode of given path starting <i>root</i> inode.
     *
     * @param dbConnection
     * @param root         staring point
     * @param path
     * @return inode or null if path does not exist.
     * @throws SQLException
     */
    FsInode path2inode(Connection dbConnection, FsInode root, String path) throws SQLException, IOException {


        File pathFile = new File(path);
        List<String> pathElemts = new ArrayList<String>();


        do {
            String fileName = pathFile.getName();
            if (fileName.length() != 0) {
                /*
                * skip multiple '/'
                */
                pathElemts.add(pathFile.getName());
            }

            pathFile = pathFile.getParentFile();
        } while (pathFile != null);

        FsInode parentInode = root;
        FsInode inode = root;
        /*
        * while list in reverse order, we have too go backward
        */
        for (int i = pathElemts.size(); i > 0; i--) {
            String f = pathElemts.get(i - 1);
            inode = inodeOf(dbConnection, parentInode, f);

            if (inode == null) {
                /*
                * element not found stop walking
                */
                break;
            }

            /*
            * if is a link, then resove it
            */
            Stat s = stat(dbConnection, inode);
            if (UnixPermission.getType(s.getMode()) == UnixPermission.S_IFLNK) {
                byte[] b = new byte[(int) s.getSize()];
                int n = read(dbConnection, inode, 0, 0, b, 0, b.length);
                String link = new String(b, 0, n);
                if (link.charAt(0) == File.separatorChar) {
                    // FIXME: have to be done more elegant
                    parentInode = new FsInode(parentInode.getFs(), "000000000000000000000000000000000000");
                }
                inode = path2inode(dbConnection, parentInode, link);
            }
            parentInode = inode;
        }

        return inode;

    }

    /**
     * creates an instance of org.dcache.chimera.&lt;dialect&gt;FsSqlDriver or
     * default driver, if specific driver not available
     *
     * @param dialect
     * @return FsSqlDriver
     */
    static FsSqlDriver getDriverInstance(String dialect) {

        FsSqlDriver driver = null;

        String dialectDriverClass = "org.dcache.chimera." + dialect + "FsSqlDriver";

        try {
            driver = (FsSqlDriver) Class.forName(dialectDriverClass).newInstance();
        } catch (InstantiationException e) {
        } catch (IllegalAccessException e) {
        } catch (ClassNotFoundException e) {
            _log.info(dialectDriverClass + " not found, using default FsSQLDriver.");
            driver = new FsSqlDriver();
        }

        return driver;
    }

}