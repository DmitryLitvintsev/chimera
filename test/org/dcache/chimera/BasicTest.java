package org.dcache.chimera;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.dcache.acl.ACE;
import org.dcache.acl.enums.AccessMask;
import org.dcache.acl.enums.AceType;
import org.dcache.acl.enums.Who;

import org.junit.Ignore;
import org.junit.Test;
import static org.junit.Assert.*;

import org.dcache.chimera.posix.Stat;
import org.dcache.chimera.store.AccessLatency;
import org.dcache.chimera.store.RetentionPolicy;

public class BasicTest extends ChimeraTestCaseHelper {

    @Test
    public void testLs() throws Exception {

        List<HimeraDirectoryEntry> list = DirectoryStreamHelper.listOf(_rootInode);
        assertTrue("Root Dir cant be empty", list.size() > 0);

    }

    @Test
    public void testMkDir() throws Exception {

        Stat stat = _rootInode.stat();
        FsInode newDir = _rootInode.mkdir("junit");

        assertEquals("mkdir have to incrise parent's nlink count by one",
                _rootInode.stat().getNlink(), stat.getNlink() + 1);
        assertTrue("mkdir have to update parent's mtime", _rootInode.stat()
                .getMTime() > stat.getMTime());
        assertEquals("new dir should have link count equal to two", newDir
                .stat().getNlink(), 2);
    }

    @Test
    public void testCreateFile() throws Exception {

        FsInode base = _rootInode.mkdir("junit");
        Stat stat = base.stat();

        FsInode newFile = base.create("testCreateFile", 0, 0, 0644);

        assertEquals("crete file have to incrise parent's nlink count by one",
                base.stat().getNlink(), stat.getNlink() + 1);

        assertTrue("crete file have to update parent's mtime", base.stat()
                .getMTime() > stat.getMTime());

        assertEquals("new file should have link count equal to two", newFile
                .stat().getNlink(), 1);
    }

    @Test
    public void testCreateFilePermission() throws Exception {

        FsInode base = _rootInode.mkdir("junit");
        int mode1 = 0644;
        int mode2 = 0755;
        FsInode file1 = base.create("testCreateFilePermission1", 0, 0, mode1);
        FsInode file2 = base.create("testCreateFilePermission2", 0, 0, mode2);

        assertEquals("creare pemissions are not respected",
                file1.stat().getMode() & UnixPermission.S_PERMS, mode1);
        assertEquals("creare pemissions are not respected",
                file2.stat().getMode() & UnixPermission.S_PERMS, mode2);

    }


    @Test // (expected=FileExistsChimeraFsException.class)
    public void testCreateFileDup() throws Exception {

        FsInode base = _rootInode.mkdir("junit");

        try {
            base.create("testCreateFile", 0, 0, 0644);
            base.create("testCreateFile", 0, 0, 0644);

            fail("you can't create a file twice");

        }catch(FileExistsChimeraFsException fee) {
            // OK
        }
    }

    @Test // (expected=FileExistsChimeraFsException.class)
    public void testCreateDirDup() throws Exception {

        FsInode base = _rootInode.mkdir("junit");

        try {
            base.mkdir("testCreateDir");
            base.mkdir("testCreateDir");

            fail("you can't create a directory twice");

        }catch(FileExistsChimeraFsException fee) {
            // OK
        }
    }

    @Test
    public void testDeleteNonEmptyDir() throws Exception {

        FsInode base = _rootInode.mkdir("junit");

        base.create("testCreateFile", 0, 0, 0644);
        assertFalse("you can't delete non empty directory", _rootInode.remove("junit") );

    }


    @Test
    public void testDeleteFile() throws Exception {

        FsInode base = _rootInode.mkdir("junit");

        base.create("testCreateFile", 0, 0, 0644);
        Stat stat = base.stat();

        base.remove("testCreateFile");

        assertEquals("remove have to decrease parents link count",base.stat().getNlink(), stat.getNlink() - 1 );
        assertFalse("remove have to update parent's mtime", stat.getMTime() == base.stat().getMTime() );

    }

    @Test
    public void testDeleteDir() throws Exception {

        FsInode base = _rootInode.mkdir("junit");

        base.create("testCreateDir", 0, 0, 0644);
        Stat stat = base.stat();

        base.remove("testCreateDir");

        assertEquals("remove have to decrease parents link count",base.stat().getNlink(), stat.getNlink() - 1 );
        assertFalse("remove have to update parent's mtime", stat.getMTime() == base.stat().getMTime() );

    }

    @Test
    public void testDeleteNonExistingFile() throws Exception {

        assertFalse("you can't delete non existing file", _rootInode.remove("testCreateFile") );
    }

    @Test
    public void testDeleteInFile() throws Exception {

        FsInode fileInode = _rootInode.create("testCreateFile", 0, 0, 0644);
        try {
            fileInode.remove("anObject");
            fail("you can't remove an  object in file Inode");
        }catch(IOHimeraFsException ioe) {
            // OK
        }
    }

    @Test
    public void testCreateInFile() throws Exception {

        FsInode fileInode = _rootInode.create("testCreateFile", 0, 0, 0644);
        try {
            fileInode.create("anObject", 0, 0, 0644);
            fail("you can't create an  object in file Inode");
        }catch(NotDirChimeraException nde) {
            // OK
        }
    }


    private final static int PARALLEL_THREADS_COUNT = 10;


    /**
     *
     * Helper class.
     *
     * the idea behind is to run a test in parallel threads.
     * number of running thread defined by <i>PARALLEL_THREADS_COUNT</i>
     * constant.
     *
     * The concept of a parallel test is following:
     *
     * a test have two <i>CountDownLatch</i> - readyToStart and testsReady.
     * <i>readyToStart</i> used to synchronize tests
     * <i>testsReady</i> used to notify test that all test are done;
     *
     */

    private static class ParallelCreateTestRunnerThread extends Thread {

        /**
         * tests root directory
         */
        private final FsInode _testRoot;
        /**
         * have to be counted down as soon as thread is done it's job
         */
        private final CountDownLatch _ready;
        /**
         * wait for 'Go'
         */
        private final CountDownLatch _waitingToStart;

        /**
         *
         * @param name of the thread
         * @param testRoot root directory of the test
         * @param ready tests ready count down
         * @param waitingToStart wait for 'Go'
         */
        public ParallelCreateTestRunnerThread(String name, FsInode testRoot, CountDownLatch ready, CountDownLatch waitingToStart) {
            super(name);
            _testRoot = testRoot;
            _ready = ready;
            _waitingToStart = waitingToStart;
        }


        @Override
        public void run() {
            try {
                _waitingToStart.await();
                _testRoot.create(Thread.currentThread().getName(), 0, 0, 0644);
            }catch(Exception hfe) {
                // FIXME
            }finally{
                _ready.countDown();
            }
        }

    }
    @Test
    public void testParallelCreate() throws Exception {

        FsInode base = _rootInode.mkdir("junit");
        Stat stat = base.stat();

        CountDownLatch readyToStart = new CountDownLatch(PARALLEL_THREADS_COUNT);
        CountDownLatch testsReady = new CountDownLatch(PARALLEL_THREADS_COUNT);

        for( int i = 0; i < PARALLEL_THREADS_COUNT; i++) {

            new ParallelCreateTestRunnerThread("TestRunner" + i, base, testsReady, readyToStart).start();
            readyToStart.countDown();
        }

        testsReady.await();
        assertEquals("new dir should have link count equal to two", base
                .stat().getNlink(), stat.getNlink() + PARALLEL_THREADS_COUNT);

    }

    @Test
    public void testHardLink() throws Exception {

        FsInode base = _rootInode.mkdir("junit");

        FsInode fileInode = base.create("hardLinkTestSourceFile", 0, 0, 0644);

        Stat stat = fileInode.stat();

        FsInode hardLinkInode = _fs.createHLink(base, fileInode, "hardLinkTestDestinationFile");

        assertEquals("hard link's  have to increase link count by one", stat.getNlink() + 1, hardLinkInode.stat().getNlink());

        boolean removed = _fs.remove(base, "hardLinkTestDestinationFile");
        assertTrue("failed to remove hard link", removed );
        assertTrue("removeing of hard link have to decrease link count by one", 1 == fileInode.stat().getNlink());

    }


    @Test
    public void testRemoveLinkToDir() throws Exception {

        FsInode base = _rootInode.mkdir("junit");

        _fs.createLink(base, "aLink", "/junit");

        boolean removed = _fs.remove(base, "aLink");
        assertTrue("failed to remove symbolic link", removed );

    }


    @Ignore
    @Test
    public void testDirHardLink() throws Exception {

        FsInode base = _rootInode.mkdir("junit");

        FsInode dirInode = base.mkdir("dirHadrLinkTestSrouceDir");

        FsInode hardLinkInode = _fs.createHLink(base, dirInode, "dirHadrLinkTestDestinationDir");
    }


    @Test
    public void testSymLink() throws Exception {

        FsInode base = _rootInode.mkdir("junit");
        FsInode fileInode = base.create("testCreateFile", 0, 0, 0644);

        try {
            fileInode.createLink("aLink", 0, 0, 0644, "../junit".getBytes());
            fail("can't create a link in non directory inode");
        }catch (NotDirChimeraException e) {
            // OK
        }
    }


    @Test
    public void testRenameNonExistSameDir() throws Exception {

        FsInode base = _rootInode.mkdir("junit");
        FsInode fileInode = base.create("testCreateFile", 0, 0, 0644);

        Stat preStatBase = base.stat();
        Stat preStatFile = fileInode.stat();

        boolean ok = _fs.move(base, "testCreateFile" , base, "testCreateFile2");



        assertTrue("can't move", ok);
        assertEquals("link count of base directory should not be modified in case of rename", preStatBase.getNlink(), base.stat().getNlink());
        assertEquals("link count of file shold not be modified in case of rename", preStatFile.getNlink(), fileInode.stat().getNlink());

    }


    @Test
    public void testRenameExistSameDir() throws Exception {

        FsInode base = _rootInode.mkdir("junit");
        FsInode fileInode = base.create("testCreateFile", 0, 0, 0644);
        FsInode file2Inode = base.create("testCreateFile2", 0, 0, 0644);

        Stat preStatBase = base.stat();

        boolean ok = _fs.move(base, "testCreateFile" , base, "testCreateFile2");

        assertTrue("can't move", ok);
        assertEquals("link count of base directory should decrease by one", preStatBase.getNlink() -1, base.stat().getNlink());

        assertFalse("ghost file", file2Inode.exists() );

    }

    @Test
    public void testRenameNonExistNotSameDir() throws Exception {

        FsInode base = _rootInode.mkdir("junit");
        FsInode base2 = _rootInode.mkdir("junit2");
        FsInode fileInode = base.create("testCreateFile", 0, 0, 0644);

        Stat preStatBase = base.stat();
        Stat preStatBase2 = base2.stat();
        Stat preStatFile = fileInode.stat();

        boolean ok = _fs.move(base, "testCreateFile" , base2, "testCreateFile2");

        assertTrue("can't move", ok);
        assertEquals("link count of source directory should decrese on move out",  preStatBase.getNlink() - 1 , base.stat().getNlink() );
        assertEquals("link count of destination directory should increase on move in", preStatBase2.getNlink() + 1 ,  base2.stat().getNlink() );
        assertEquals("link count of file shold not be modified on move", preStatFile.getNlink(), fileInode.stat().getNlink());

    }


    @Test
    public void testRenameExistNotSameDir() throws Exception {

        FsInode base = _rootInode.mkdir("junit");
        FsInode base2 = _rootInode.mkdir("junit2");
        FsInode fileInode = base.create("testCreateFile", 0, 0, 0644);
        FsInode fileInode2 = base2.create("testCreateFile2", 0, 0, 0644);

        Stat preStatBase = base.stat();
        Stat preStatBase2 = base2.stat();
        Stat preStatFile = fileInode.stat();

        boolean ok = _fs.move(base, "testCreateFile" , base2, "testCreateFile2");

        assertTrue("can't move", ok);
        assertEquals("link count of source directory should decrese on move out",  preStatBase.getNlink() - 1 , base.stat().getNlink() );
        assertEquals("link count of destination directory should not be modified on replace", preStatBase2.getNlink() ,  base2.stat().getNlink() );
        assertEquals("link count of file shold not be modified on move", preStatFile.getNlink(), fileInode.stat().getNlink());

        assertFalse("ghost file", fileInode2.exists() );
    }


    @Test
    public void testRenameHardLinkToItselfSameDir() throws Exception  {


        FsInode base = _rootInode.mkdir("junit");
        FsInode fileInode = base.create("testCreateFile", 0, 0, 0644);
        FsInode linkInode = _fs.createHLink(base, fileInode, "testCreateFile2");

        Stat preStatBase = base.stat();
        Stat preStatFile = fileInode.stat();

        boolean ok = _fs.move(base, "testCreateFile" , base, "testCreateFile2");

        assertTrue("rename of hardlink to itself should always return ok", ok);
        assertEquals("link count of base directory should not be modified in case of rename", preStatBase.getNlink(), base.stat().getNlink());
        assertEquals("link count of file shold not be modified in case of rename", preStatFile.getNlink(), fileInode.stat().getNlink());

    }

    @Test
    public void testRenameHardLinkToItselfNotSameDir() throws Exception  {


        FsInode base = _rootInode.mkdir("junit");
        FsInode base2 = _rootInode.mkdir("junit2");
        FsInode fileInode = base.create("testCreateFile", 0, 0, 0644);
        FsInode linkInode = _fs.createHLink(base2, fileInode, "testCreateFile2");

        Stat preStatBase = base.stat();
        Stat preStatBase2 = base2.stat();
        Stat preStatFile = fileInode.stat();

        boolean ok = _fs.move(base, "testCreateFile" , base2, "testCreateFile2");

        assertTrue("rename of hardlink to itself should always return ok", ok);
        assertEquals("link count of source directory should not be modified in case of rename", preStatBase.getNlink(), base.stat().getNlink());
        assertEquals("link count of destination directory should not be modified in case of rename", preStatBase2.getNlink(), base2.stat().getNlink());
        assertEquals("link count of file shold not be modified in case of rename", preStatFile.getNlink(), fileInode.stat().getNlink());

    }

    @Test
    public void testRemoveNonexistgById() throws Exception  {

        FsInode inode = new FsInode(_fs, "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");
        assertFalse("was able to remove non existing entry",   _fs.remove(inode) );
    }

    @Test
    public void testRemoveNonexistgByPath() throws Exception  {
    	FsInode base = _rootInode.mkdir("junit");
        assertFalse("was able to remove non existing entry",   _fs.remove(base, "notexist") );
    }

    @Test
    public void testAddLocationForNonexistong() throws Exception  {
        FsInode inode = new FsInode(_fs, "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");
        try {
            _fs.addInodeLocation(inode, StorageGenericLocation.DISK, "/dev/null");
            fail("was able to add cache location for non existing file");
        }catch (FileNotFoundHimeraFsException e) {
            // OK
        }
    }

    @Test
    public void testDupAddLocation() throws Exception  {

        FsInode base = _rootInode.mkdir("junit");

        FsInode fileInode = base.create("testCreateFile", 0, 0, 0644);
        _fs.addInodeLocation(fileInode, StorageGenericLocation.DISK, "/dev/null");
        _fs.addInodeLocation(fileInode, StorageGenericLocation.DISK, "/dev/null");
    }

    @Ignore
    @Test
    public void testSetSizeNotExist() throws Exception {

        FsInode inode = new FsInode(_fs, "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");
         try {
            _fs.setFileSize(inode, 0);
            fail("was able set size for non existing file");
        }catch (FileNotFoundHimeraFsException e) {
            // OK
        }
    }

    @Ignore
    @Test
    public void testChowneNotExist() throws Exception {

        FsInode inode = new FsInode(_fs, "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");
         try {
            _fs.setFileOwner(inode, 3750);
            fail("was able set owner for non existing file");
        }catch (FileNotFoundHimeraFsException e) {
            // OK
        }
    }


    @Test
    public void testUpdateLevelNotExist() throws Exception {
        FsInode inode = new FsInode(_fs, "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");
        try {
            byte[] data = "bla".getBytes();
           _fs.write(inode, 1, 0, data, 0, data.length);
           fail("was able to update level of non existing file");
       }catch (FileNotFoundHimeraFsException e) {
           // OK
       }
    }

    
    @Test
    public void testUpdateChecksumNotExist() throws Exception {
        FsInode inode = new FsInode(_fs, "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");
        try {
           _fs.setInodeChecksum(inode, 1, "asum");
           fail("was able to update checksum of non existing file");
       }catch (FileNotFoundHimeraFsException e) {
           // OK
       }
    }

    @Test
    public void testUpdateChecksum() throws Exception {
        String sum = "asum";

        FsInode base = _rootInode.mkdir("junit");
        FsInode fileInode = base.create("testCreateFile", 0, 0, 0644);
        _fs.setInodeChecksum(fileInode, 1, sum);
        assertEquals("Checksum set/get miss match", sum, _fs.getInodeChecksum(fileInode, 1));
    }

    @Ignore
    @Test
    public void testUpdateChecksumDifferTypes() throws Exception {
        String sum1 = "asum1";
        String sum2 = "asum2";
        
        FsInode base = _rootInode.mkdir("junit");
        FsInode fileInode = base.create("testCreateFile", 0, 0, 0644);
        _fs.setInodeChecksum(fileInode, 1, sum1);
        _fs.setInodeChecksum(fileInode, 2, sum2);
        assertEquals("Checksum set/get miss match", sum1, _fs.getInodeChecksum(fileInode, 1));
        assertEquals("Checksum set/get miss match", sum2, _fs.getInodeChecksum(fileInode, 2));
    }

    @Test
    public void testUpdateALNonExist() throws Exception {
        FsInode inode = new FsInode(_fs, "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");
        try {
           _fs.setAccessLatency(inode, AccessLatency.ONLINE);
           fail("was able to update access latency of non existing file");
       }catch (FileNotFoundHimeraFsException e) {
           // OK
       }
    }

    @Test
    public void testUpdateALExist() throws Exception {
        FsInode base = _rootInode.mkdir("junit");
        FsInode inode = base.create("testCreateFile", 0, 0, 0644);

        _fs.setAccessLatency(inode, AccessLatency.ONLINE);
    }

    @Test
    public void testChangeALExist() throws Exception {
        FsInode base = _rootInode.mkdir("junit");
        FsInode inode = base.create("testCreateFile", 0, 0, 0644);

        _fs.setAccessLatency(inode, AccessLatency.ONLINE);
        _fs.setAccessLatency(inode, AccessLatency.NEARLINE);
    }

    @Test
    public void testUpdateRPNonExist() throws Exception {
        FsInode inode = new FsInode(_fs, "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");
        try {
           _fs.setRetentionPolicy(inode, RetentionPolicy.CUSTODIAL);
           fail("was able to update retention policy of non existing file");
       }catch (FileNotFoundHimeraFsException e) {
           // OK
       }
    }

    @Test
    public void testUpdateRPExist() throws Exception {
        FsInode base = _rootInode.mkdir("junit");
        FsInode inode = base.create("testCreateFile", 0, 0, 0644);

        _fs.setRetentionPolicy(inode, RetentionPolicy.CUSTODIAL);
    }

    @Test
    public void testChangeRPExist() throws Exception {
        FsInode base = _rootInode.mkdir("junit");
        FsInode inode = base.create("testCreateFile", 0, 0, 0644);

        _fs.setRetentionPolicy(inode, RetentionPolicy.CUSTODIAL);
        _fs.setRetentionPolicy(inode, RetentionPolicy.OUTPUT);
    }

    @Test
    public void testResoveLinkOnPathToId() throws Exception {

        FsInode dirInode = _rootInode.mkdir("testDir", 0, 0, 0755);
        FsInode linkInode = _rootInode.createLink("aLink", 0, 0, 055, "testDir".getBytes());

        FsInode inode = _fs.path2inode("aLink", _rootInode);
        assertEquals("Link resolution did not work", dirInode, inode);

    }

    @Test
    public void testResoveLinkOnPathToIdRelative() throws Exception {

        FsInode dirInode = _rootInode.mkdir("testDir", 0, 0, 0755);
        FsInode linkInode = _rootInode.createLink("aLink", 0, 0, 055, "../testDir".getBytes());

        FsInode inode = _fs.path2inode("aLink", _rootInode);
        assertEquals("Link resolution did not work", dirInode, inode);

    }

    @Test
    public void testResoveLinkOnPathToIdAbsolute() throws Exception {

        FsInode dirInode = _rootInode.mkdir("testDir", 0, 0, 0755);
        FsInode subdirInode = dirInode.mkdir("testDir2", 0, 0, 0755);
        FsInode linkInode = dirInode.createLink("aLink", 0, 0, 055, "/testDir/testDir2".getBytes());

        FsInode inode = _fs.path2inode("aLink", dirInode);
        assertEquals("Link resolution did not work", subdirInode, inode);

    }

    @Test
    public void testUpdateCtimeOnSetOwner() throws Exception {
        FsInode dirInode = _rootInode.mkdir("testDir", 0, 0, 0755);
        long oldCtime = dirInode.stat().getCTime();

        TimeUnit.MILLISECONDS.sleep(2);
        dirInode.setUID(3750);
        assertTrue("The ctime is not updated", dirInode.stat().getCTime() > oldCtime);
    }

    @Test
    public void testUpdateCtimeOnSetGroup() throws Exception {
        FsInode dirInode = _rootInode.mkdir("testDir", 0, 0, 0755);
        long oldCtime = dirInode.stat().getCTime();

        TimeUnit.MILLISECONDS.sleep(2);
        dirInode.setGID(3750);
        assertTrue("The ctime is not updated", dirInode.stat().getCTime() > oldCtime);
    }

    @Test
    public void testUpdateCtimeOnSetMode() throws Exception {
        FsInode dirInode = _rootInode.mkdir("testDir", 0, 0, 0755);
        long oldCtime = dirInode.stat().getCTime();

        TimeUnit.MILLISECONDS.sleep(2);
        dirInode.setMode(0700);
        assertTrue("The ctime is not updated", dirInode.stat().getCTime() > oldCtime);
    }

    @Test
    public void testUpdateMtimeOnSetSize() throws Exception {
        FsInode dirInode = _rootInode.mkdir("testDir", 0, 0, 0755);
        long oldMtime = dirInode.stat().getMTime();

        TimeUnit.MILLISECONDS.sleep(2);
        dirInode.setSize(17);
        assertTrue("The mtime is not updated", dirInode.stat().getMTime() > oldMtime);
    }

    @Test
    public void testSetAcl() throws Exception {
        FsInode dirInode = _rootInode.mkdir("testDir", 0, 0, 0755);

        List<ACE> aces = new ArrayList<ACE>();

        aces.add(new ACE(AceType.ACCESS_DENIED_ACE_TYPE, 0,
                AccessMask.ADD_SUBDIRECTORY.getValue(), Who.USER, 1001,
                ACE.DEFAULT_ADDRESS_MSK));

        aces.add(new ACE(AceType.ACCESS_ALLOWED_ACE_TYPE, 0,
                AccessMask.ADD_FILE.getValue(), Who.USER, 1001,
                ACE.DEFAULT_ADDRESS_MSK));

        _fs.setACL(dirInode, aces);
        List<ACE> l2 = _fs.getACL(dirInode);
        assertEquals(aces, l2);
    }

    @Test
    public void testReSetAcl() throws Exception {
        FsInode dirInode = _rootInode.mkdir("testDir", 0, 0, 0755);

        List<ACE> aces = new ArrayList<ACE>();

        aces.add(new ACE(AceType.ACCESS_DENIED_ACE_TYPE, 0,
                AccessMask.ADD_SUBDIRECTORY.getValue(), Who.USER, 1001,
                ACE.DEFAULT_ADDRESS_MSK));

        aces.add(new ACE(AceType.ACCESS_ALLOWED_ACE_TYPE, 0,
                AccessMask.ADD_FILE.getValue(), Who.USER, 1001,
                ACE.DEFAULT_ADDRESS_MSK));

        _fs.setACL(dirInode, aces);
        _fs.setACL(dirInode, new ArrayList<ACE>());
        assertTrue(_fs.getACL(dirInode).isEmpty());
    }

    @Test(expected = org.dcache.chimera.FileNotFoundHimeraFsException.class)
    public void testGetInodeByPathNotExist() throws Exception {
        _fs.path2inode("/some/nonexisting/path");
        fail("Expected exception not thrown");
    }
}
