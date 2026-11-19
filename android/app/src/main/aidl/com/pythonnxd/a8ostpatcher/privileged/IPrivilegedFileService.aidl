package com.pythonnxd.a8ostpatcher.privileged;

import android.os.ParcelFileDescriptor;

interface IPrivilegedFileService {
    void destroy() = 16777114;
    boolean exists(String path) = 1;
    boolean isDirectory(String path) = 2;
    boolean isFile(String path) = 3;
    long length(String path) = 4;
    String[] listEntries(String dirPath) = 5;
    boolean mkdirs(String path) = 6;
    boolean deletePath(String path) = 7;
    boolean testWrite(String dirPath) = 8;
    ParcelFileDescriptor openRead(String path) = 9;
    ParcelFileDescriptor openWrite(String path) = 10;
}
