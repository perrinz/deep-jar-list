package com.perrinz.deepjarlist;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class DeepJarList {

    public static void main(String[] args) throws IOException {
        if(args.length == 0) usage();
        int fileListIndex = 0;
        String filter = null;
        while(fileListIndex < args.length && args[fileListIndex].charAt(0) == '-') {
            switch(args[fileListIndex].charAt(1)) {
                case 'm': showManifest = true; break;
                case 'l': showLineNos = true; break;
                case 'x': extensions.add("xml"); break;
                case 'z': showFileSize = true; break;
                case '5': showFileHash = true; break;
                case 'e': if (++fileListIndex == args.length) usage(); else extensions.addAll(Arrays.asList(args[fileListIndex].toLowerCase().split(","))); break;
                case 'f': if (++fileListIndex == args.length) usage(); else filter = args[fileListIndex]; break;
                default: usage();
            }
            fileListIndex++;
        }

        if(fileListIndex == args.length) usage();

        pattern = filter == null ? null : Pattern.compile(filter);

        for(int i = fileListIndex; i < args.length; i++) {
            File file = new File(args[i]);
            if (!file.exists()) {
                System.out.println("warning: file " + file.getPath() + " does not exist, skipping");
            }
            else {
                ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(file));
                System.out.println(file.getName() + " = [");
                listEntries(zipInputStream, 1);
                System.out.println("]");
                zipInputStream.close();
            }
        }
    }

    private static void usage() {
        System.out.println("\nusage: DeepJarList [-m] [-x|-e <extensions>] [-l] [-f <regex>] <jar files>\n");
        System.out.println("  -m will show contents of manifest files");
        System.out.println("  -e will show contents of files with extension matching given comma-delimited list");
        System.out.println("  -f will only display file names matching regex");
        System.out.println("  -x will show contents of XML files (equivalent to -e xml)");
        System.out.println("  -l will show line numbers for displayed files");
        System.out.println("  -z will show file size for each file");
        System.out.println("  -5 will show md5 hash for each file");
        System.out.println();
        System.exit(1);
    }

    private static void listEntries(ZipInputStream zipInputStream, int level) throws IOException {
        final String padding = "                                    ".substring(0, level * 4);
        int excludedByPattern = 0;
        while (true) {
            ZipEntry zipEntry = zipInputStream.getNextEntry();
            if(zipEntry == null) break;
            if(zipEntry.isDirectory()) {
                if(pattern == null || pattern.matcher(zipEntry.getName()).matches()) {
                    System.out.println(padding + zipEntry.getName());
                }
                continue;
            }
            byte b1 = (byte)zipInputStream.read();
            byte b2 = (byte)zipInputStream.read();
            byte b3 = (byte)zipInputStream.read();
            byte b4 = (byte)zipInputStream.read();
            boolean isJar = b1 == 0x50 && b2 == 0x4B && b3 == 0x03 && b4 == 0x04;  // magic number 'PK\003\004'
            int lastDotPos = zipEntry.getName().lastIndexOf(".");
            boolean showFile = lastDotPos > 0 && extensions.contains(zipEntry.getName().toLowerCase().substring(lastDotPos+1)) ||
                    showManifest && zipEntry.getName().toLowerCase().endsWith("manifest.mf");
            if (isJar || showFile) {
                System.out.print(padding + zipEntry.getName());
                if(zipEntry.getSize() > 134217728L) { // 128MB limit
                    System.out.println(" = [ Skipping file -- too large. ]");
                }
                else {
                    byte[] fileBytes = readFileBytes(zipEntry, b1, b2, b3, b4, zipInputStream);
                    System.out.println(fileInfo(fileBytes) + " = [");
                    ByteArrayInputStream bais = new ByteArrayInputStream(fileBytes);
                    if(isJar) {
                        listEntries(new ZipInputStream(bais), level + 1);
                    }
                    else { // showFile
                        if(showLineNos) {
                            LineNumberReader lnr = new LineNumberReader(new InputStreamReader(bais));
                            String line;
                            while((line = lnr.readLine()) != null) {
                                System.out.println(padding + "    ".substring(Math.min(String.valueOf(lnr.getLineNumber()).length(),4)) + lnr.getLineNumber() + " " + line);
                            }
                        }
                        else {
                            BufferedReader br = new BufferedReader(new InputStreamReader(bais));
                            String line;
                            while((line = br.readLine()) != null) System.out.println(padding+"    " + line);
                        }
                    }
                }
                System.out.println(padding + "]");
            }
            else {
                if(pattern == null) {
                    System.out.println(padding + zipEntry.getName() + fileInfo(zipEntry, b1, b2, b3, b4, zipInputStream));
                }
                else {
                    if (pattern.matcher(zipEntry.getName()).matches()) {
                        System.out.println(padding + zipEntry.getName() + fileInfo(zipEntry, b1, b2, b3, b4, zipInputStream));
                    }
                    else {
                        excludedByPattern++;
                    }
                }
            }
            zipInputStream.closeEntry();
        }
        zipInputStream.close();
        if(excludedByPattern > 0) {
            System.out.println(padding + "(" + excludedByPattern + " files excluded by filter)");
        }
    }

    private static byte[] readFileBytes(ZipEntry zipEntry, byte b1, byte b2, byte b3, byte b4, InputStream zipInputStream) throws IOException {
        final int size = (int) zipEntry.getSize();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(size > 0 ? size : 65536); // size is sometimes -1, use arbitrary initial array size
        baos.write(new byte[]{b1, b2, b3, b4});
        byte[] fileData = new byte[4096];
        while (true) {
            int read = zipInputStream.read(fileData, 0, 4096);
            if (read < 0) break;
            baos.write(fileData, 0, read);
        }
        return baos.toByteArray();
    }

    private static String fileInfo(ZipEntry zipEntry, byte b1, byte b2, byte b3, byte b4, InputStream zipInputStream) throws IOException {
        if (!showFileSize && !showFileHash) return "";
        return fileInfo(readFileBytes(zipEntry, b1, b2, b3, b4, zipInputStream));
    }

    private static String fileInfo(byte[] fileBytes) {
        if (!showFileSize && !showFileHash) return "";
        StringBuilder fileInfo = new StringBuilder();
        if(showFileSize) fileInfo.append("  (").append(fileBytes.length).append(" bytes)");
        if(showFileHash) {
            fileInfo.append("  ");
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] hash = md.digest(fileBytes);
                for (byte aHash : hash) {
                    int c = ((char) aHash & 0xff);
                    fileInfo.append(Integer.toHexString(c >> 4).toLowerCase()).append(Integer.toHexString(c & 0x0f).toLowerCase());
                }
            }
            catch (Exception e) {
                return fileInfo.append("[?]").toString();
            }
        }
        return fileInfo.toString();
    }

    private static boolean showManifest;
    private static HashSet<String> extensions = new HashSet<String>();
    private static boolean showLineNos;
    private static Pattern pattern;
    private static boolean showFileSize;
    private static boolean showFileHash;
}
