/*
 * This file is part of MultiROM Manager.
 *
 * MultiROM Manager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MultiROM Manager is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with MultiROM Manager. If not, see <http://www.gnu.org/licenses/>.
 */

package com.tassadar.multirommgr;

import android.content.ContentValues;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.tassadar.multirommgr.romlistwidget.RomListDataProvider;
import com.tassadar.multirommgr.romlistwidget.RomListOpenHelper;
import com.tassadar.multirommgr.romlistwidget.RomListWidgetProvider;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import eu.chainfire.libsuperuser.Shell;

public class MultiROM {
    private static final String TAG = "MROMMgr::MultiROM";

    // Minimum MultiROM version which is able to boot ROMs
    // via --boot-rom argument
    public static final String MIN_BOOT_ROM_VER = "19g";

    public static final int MAX_ROM_NAME = 26;
    public static final String INTERNAL_ROM = "Internal";
    private static final String UTOUCH_ROM_INFO = "ubuntu_touch.txt";

    public boolean findMultiROMDir() {
        List<String> out = Shell.SU.run(
                "folders=\"/data/media/0/multirom/ /data/media/multirom/\";" +
                "for f in $folders; do" +
                "    if [ -d \"$f\" ]; then" +
                "        echo \"$f\";" +
                "        exit 0;" +
                "    fi;" +
                "done;");

        if (out == null || out.isEmpty())
            return false;

        m_path = out.get(0);
        Log.d(TAG, "Found in path " + m_path);
        if(Utils.isSELinuxEnforcing()) {
            Utils.chcon(Utils.CHCON_EXECUTABLE,
                    m_path + "multirom",
                    m_path + "trampoline",
                    m_path + "busybox",
                    m_path + "kexec");
        }
        return true;
    }

    public boolean findVersion() {
        List<String> out = Shell.SU.run(m_path + "multirom -v");
        if (out == null || out.isEmpty())
            return false;

        m_version = out.get(0);
        Log.d(TAG, "MultiROM version: " + m_version);

        if (m_version.contains("apkL")) {
            // legacy apkL support (this can be deleted, it was only a "Temp... will be deleted" on github)
            int pos = m_version.indexOf("apkL");
            if (m_version.length() > pos + 4)
                m_apkL_version = Integer.valueOf(m_version.substring(pos + 4));
            else
                m_apkL_version = 1;
            m_version = m_version.substring(0, pos - 1).trim();
        }
        else if (out.size() > 1 && out.get(1).contains("apkL")) {
            // proper usage of apkL which mantains backwards compatibility with the non-apkL aware app
            int pos = out.get(1).indexOf("apkL");
            if (out.get(1).length() > pos + 4)
                m_apkL_version = Integer.valueOf(out.get(1).substring(pos + 4));
            else
                m_apkL_version = 1;
        }
        else
            m_apkL_version = 0;

        return true;
    }

    private String findInternalRomName() {
        List<String> out = Shell.SU.run("\'%s/busybox\' cat \"%s/multirom.ini\"", m_path, m_path);
        if (out == null || out.isEmpty())
            return INTERNAL_ROM;

        String entry;

        m_no_kexec = false;
        for(int i = 0; i < out.size(); ++i) {
            entry = out.get(i).trim();
            if (entry.startsWith("no_kexec=")) {
                m_no_kexec = true;
            }
        }

        for(int i = 0; i < out.size(); ++i) {
            entry = out.get(i).trim();
            if (entry.startsWith("int_display_name=")) {
                entry = entry.substring(17); // strlen("int_display_name=");
                if(!entry.isEmpty())
                    return entry;
            }
        }
        return INTERNAL_ROM;
    }

    public boolean find_no_kexec() {
        findInternalRomName();
        return m_no_kexec;
    }

    public void findRoms() {
        String internal = findInternalRomName();

        if (get_apkL_version() == 0) {
            List<String> out = Shell.SU.run("\'%s/busybox\' ls -1 -p \"%s/roms/\"", m_path, m_path);
            if (out == null || out.isEmpty())
                return;

            Rom rom;
            int type;
            String name;
            String base_path;

            for(int i = 0; i < out.size(); ++i) {
                name = out.get(i);
                if(!name.endsWith("/"))
                    continue;

                name = name.substring(0, name.length() - 1);
                if(name.equals(INTERNAL_ROM)) {
                    name = internal;
                    type = Rom.ROM_PRIMARY;
                } else {
                    type = Rom.ROM_SECONDARY;
                }

                if (type == Rom.ROM_PRIMARY)
                    base_path = m_path + "roms/" + INTERNAL_ROM;
                else
                    base_path = m_path + "roms/" + name;

                rom = new Rom(name, type, 0, base_path, "", "", "", "", "");
                m_roms.add(rom);
            }
        }
        else {
            // let the multirom binary enumerate all roms (includes mounting external partitions and listing them too)
            List<String> out = Shell.SU.run(m_path + "multirom -apkL");
            if (out == null || out.isEmpty())
                return;

            Rom rom;
            int type;
            String name;

            String RomInfoLine;
            int p1, p2, p3, p4, p5, p6, p7, p8;

            int active;
            String base_path;
            String icon_path;
            String partition_name;
            String partition_mount_path;
            String partition_uuid;
            String partition_fs;

            for(int i = 0; i < out.size(); ++i) {
                RomInfoLine = out.get(i);
                // RomInfoLine format passed back by 'multirom -l'
                // for internal storage ROMs (no partition info):
                //    "ROM: name=%s base=%s icon=%s\n"
                // for external ROMs (partition info needed for reboot):
                //    "ROM: name=%s base=%s icon=%s part_name=%s part_mount=%s part_uuid=%s part_fs=%s\n"
                // note that we actually don't need all the information, but might as well pass
                // everything back for possible future uses.

                if(!RomInfoLine.startsWith("ROM:"))
                    continue;

                if(RomInfoLine.startsWith("ROM:1"))
                    active = 1;
                else
                    active = 0;

                p1 = RomInfoLine.indexOf("name=");
                p2 = RomInfoLine.indexOf("base=");
                p3 = RomInfoLine.indexOf("icon=");

                p4 = RomInfoLine.indexOf("part_name=");
                if (p4 < 0) {
                    p4 = RomInfoLine.length() + 1;

                    partition_name       = "";
                    partition_mount_path = "";
                    partition_uuid       = "";
                    partition_fs         = "";
                } else {
                    p5 = RomInfoLine.indexOf("part_mount=");
                    p6 = RomInfoLine.indexOf("part_uuid=");
                    p7 = RomInfoLine.indexOf("part_fs=");
                    p8 = RomInfoLine.length() + 1;

                    partition_name       = RomInfoLine.substring(p4 + 10, p5 - 1);
                    partition_mount_path = RomInfoLine.substring(p5 + 11, p6 - 1);
                    partition_uuid       = RomInfoLine.substring(p6 + 10, p7 - 1);
                    partition_fs         = RomInfoLine.substring(p7 +  8, p8 - 1);
                }

                name      = RomInfoLine.substring(p1 +  5, p2-1);
                base_path = RomInfoLine.substring(p2 +  5, p3-1);
                icon_path = RomInfoLine.substring(p3 +  5, p4-1);

                if(name.equals(INTERNAL_ROM)) {
                    name = internal;
                    type = Rom.ROM_PRIMARY;
                } else {
                    type = Rom.ROM_SECONDARY;
                }

                rom = new Rom(name, type, active, base_path, icon_path, partition_name, partition_mount_path, partition_uuid, partition_fs);
                m_roms.add(rom);
            }
        }

        Collections.sort(m_roms, new Rom.NameComparator());

        loadRomIconData();
        storeRomDataToProvider();
    }

    private void loadRomIconData() {
        // Prepare all loaded ROM paths to be used in for loop
        String list_of_rom_paths = "";
        for(Rom rom : m_roms)
            list_of_rom_paths += "\"" + rom.base_path + "\" ";

        // Load icon data
        List<String> out = Shell.SU.run(
                "IFS=$'\\n'; " +
                "for d in %s; do " +
                "    ([ ! -d \"$d\" ]) && continue;" +
                "    ([ ! -f \"$d/.icon_data\" ]) && continue;" +
                "    echo \"ROM:$d\";" +
                "    cat \"$d/.icon_data\";" +
                "done;",
                list_of_rom_paths);

        if (out == null || out.isEmpty())
            return;

        Resources res = MgrApp.getAppContext().getResources();
        Rom rom;
        String line;
        int type;
        Set<String> presentHashes = new HashSet<String>();
        for(int i = 0; i+2 < out.size(); ++i) {
            rom = null;
            line = out.get(i);

            if(!line.startsWith("ROM:"))
                continue;

            line = line.substring(4);

            //name=Internal base=/data/media/0/multirom/roms/Internal
            //compare to /multirom/roms/Internal, since externals would be on multirom-device/RomName
            type = line.endsWith("/multirom/roms/" + INTERNAL_ROM) ? Rom.ROM_PRIMARY : Rom.ROM_SECONDARY;
            for(Rom r : m_roms) {
                if (r.type == type && (type == Rom.ROM_PRIMARY || line.equals(r.base_path)))
                    rom = r;
            }

            if(rom == null)
                continue;

            line = out.get(++i);
            if(line.equals("predef_set")) {
                line = out.get(++i);
                line = MgrApp.replaceDebugPkgName(line, false);
                rom.icon_id = res.getIdentifier(line, null, null);
                rom.icon_hash = null;

                if(rom.icon_id == 0)
                    rom.icon_id = R.drawable.romic_default;
            } else if(line.equals("user_defined")) {
                rom.icon_id = R.id.user_defined_icon;
                rom.icon_hash = out.get(++i);

                presentHashes.add(rom.icon_hash);
            }
        }

        deleteUnusedIcons(presentHashes);

        // Load list of predefined icons multirom has installed
        m_predefIcons = Shell.SU.run("IFS=$'\\n'; \"%s/busybox\" ls -1 \"%s/icons\";", m_path, m_path);
    }

    private void storeRomDataToProvider() {
        Rom rom;
        Context ctx = MgrApp.getAppContext();
        Resources res = ctx.getResources();
        ContentValues[] vals = new ContentValues[m_roms.size()];

        for(int i = 0; i < m_roms.size(); ++i) {
            rom = m_roms.get(i);
            vals[i] = new ContentValues();
            vals[i].put(RomListOpenHelper.KEY_NAME, rom.name);
            vals[i].put(RomListOpenHelper.KEY_TYPE, rom.type);
            vals[i].put(RomListOpenHelper.KEY_ACTIVE, rom.active);
            vals[i].put(RomListOpenHelper.KEY_BASE_PATH, rom.base_path);
            vals[i].put(RomListOpenHelper.KEY_ICON_PATH, rom.icon_path);
            vals[i].put(RomListOpenHelper.KEY_PARTITION_NAME, rom.partition_name);
            vals[i].put(RomListOpenHelper.KEY_PARTITION_MOUNT_PATH, rom.partition_mount_path);
            vals[i].put(RomListOpenHelper.KEY_PARTITION_UUID, rom.partition_uuid);
            vals[i].put(RomListOpenHelper.KEY_PARTITION_FS, rom.partition_fs);
            vals[i].put(RomListOpenHelper.KEY_PARTITION_INFO, rom.partition_info);

            if(rom.icon_hash != null)
                vals[i].put(RomListOpenHelper.KEY_ICON_NAME, rom.icon_hash);
            else
                vals[i].put(RomListOpenHelper.KEY_ICON_NAME, res.getResourceName(rom.icon_id));
        }

        ctx.getContentResolver().delete(RomListDataProvider.CONTENT_URI, null, null);
        ctx.getContentResolver().bulkInsert(RomListDataProvider.CONTENT_URI, vals);

        RomListWidgetProvider.notifyChanged();
    }

    public void deleteUnusedIcons(Set<String> usedIconHashes) {
        String hash;
        File iconDir = Rom.getIconsDir();
        if(iconDir == null)
            return;

        File[] files = iconDir.listFiles();
        if(files == null || files.length == 0)
            return;

        for(File f : files) {
            hash = f.getName();

            if(!hash.endsWith(".png"))
                continue;

            hash = hash.substring(0, hash.length()-4); // remove .png
            if(!usedIconHashes.contains(hash))
                f.delete();
        }
    }

    public void renameRom(Rom rom, String new_name) {
        if(rom.type == Rom.ROM_PRIMARY) {
            String b = Utils.extractAsset("busybox");
            if(b == null) {
                Log.e(TAG, "Failed to extract busybox!");
                return;
            }

            Shell.SU.run(
                    "cd \"%s\";" +
                    "if [ \"$(\'%s/busybox\' grep 'int_display_name=.*' multirom.ini)\" ]; then" +
                    "    '%s/busybox' sed -i -e 's/int_display_name=.*/int_display_name=%s/g' multirom.ini;" +
                    "else" +
                    "    echo 'int_display_name=%s' >> multirom.ini;" +
                    "fi",
                    m_path, m_path, m_path, new_name, new_name);
        } else {
            Shell.SU.run("cd \"%s/..\" && mv '%s' '%s'", rom.base_path, rom.name, new_name);
        }
    }

    public void eraseROM(Rom rom) {
        if(rom.type == Rom.ROM_PRIMARY) {
            Log.e(TAG, "Attempted to delete primary ROM!");
            return;
        }

        Shell.SU.run("'%s/busybox' chattr -R -i '%s'; '%s/busybox' rm -rf '%s'",
                m_path, rom.base_path, m_path, rom.base_path);
    }

    public void bootRom(Rom rom) {
        String name = (rom.type == Rom.ROM_PRIMARY) ? INTERNAL_ROM : rom.name;
        if (rom.partition_uuid.isEmpty())
            Shell.SU.run("%s/multirom --boot-rom='%s'", m_path, name);
        else
            Shell.SU.run("%s/multirom --boot-rom='%s++uuid=%s'", m_path, name, rom.partition_uuid);
    }

    public boolean isKexecNeededFor(Rom rom) {
        if(rom.type == Rom.ROM_PRIMARY)
            return false;

        // if android ROM check for boot.img, else kexec
        List<String> out = Shell.SU.run(String.format(
                "cd \"%s\"; " +
                "if [ -d boot ] && [ -d system ] && [ -d data ] && [ -d cache ]; then" +
                "    if [ -e boot.img ]; then" +
                "        echo kexec;" +
                "    else" +
                "        echo normal;" +
                "    fi;" +
                "else" +
                "    echo kexec;" +
                "fi;",
                rom.base_path));

        if (out == null || out.isEmpty()) {
            Log.e(TAG, "Failed to check for kexec in ROM " + rom.name);
            return true;
        }

        return out.get(0).equals("kexec");
    }

    public boolean hasBootRomReqMultiROM() {
        int[] my = parseMultiRomVersions(m_version);
        int[] req = parseMultiRomVersions(MultiROM.MIN_BOOT_ROM_VER);
        return (my[0] > req[0]) || (my[0] == req[0] && my[1] >= req[1]);
    }

    public static int[] parseMultiRomVersions(String ver) {
        int[] res = { 0, 0 };
        if(!Utils.isNumeric(ver.charAt(ver.length()-1))) {
            res[1] = (int)ver.charAt(ver.length()-1);
            ver = ver.substring(0, ver.length()-1);
        }
        try {
            res[0] = Integer.valueOf(ver);
        } catch(NumberFormatException e) {
            e.printStackTrace();
        }
        return res;
    }

    public String getNewRomFolder(String base) {
        if(base.length() > MAX_ROM_NAME)
            base = base.substring(0, MAX_ROM_NAME);
        base = base.replace('/', '_');

        List<String> out = Shell.SU.run(String.format(
                "cd \"%s/roms\"; " +
                "rom='%s'; c=0; " +
                "while [ $c -lt 10 ]; do" +
                "    if [ ! -d \"$rom\" ]; then" +
                "        echo \"$(pwd)/$rom\";" +
                "        exit 0;" +
                "    fi;" +
                "    c=$(($c+1));" +
                "    rom=\"${rom%%?}$c\";" +
                "done",
                m_path, base));

        if (out == null || out.isEmpty())
            return null;
        return out.get(0);
    }

    public boolean initUbuntuDir(String path) {
        List<String> out = Shell.SU.run(String.format(
                "mkdir -p '%s' && cd '%s' && " +
                "mkdir system data cache && " +
                "mkdir cache/recovery && " +
                "cat ../../infos/%s > rom_info.txt &&" +
                "echo success",
                path, path, UTOUCH_ROM_INFO));

        if (out == null || out.isEmpty() || !out.get(0).equals("success"))
            return false;
        return true;
    }

    public int getFreeSpaceMB() {
        List<String> out = Shell.SU.run("\'%s/busybox\' df -Pm '%s'", m_path, m_path);
        if (out == null || out.size() < 2 || !out.get(0).startsWith("Filesystem"))
            return -1;

        String l = out.get(1);
        if(!l.startsWith("/dev"))
            return -1;

        String[] tokens = l.split("[ \t]+");
        if(tokens.length != 6)
            return -1;

        try {
            return Integer.parseInt(tokens[3]);
        } catch(NumberFormatException e) {
            e.printStackTrace();
            return -1;
        }
    }

    public void setRomIcon(Rom rom, String path) {
        BufferedInputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(path));
            setRomIcon(rom, in);
        } catch(IOException e) {
            e.printStackTrace();
        } finally {
            Utils.close(in);
        }
    }

    public void setRomIcon(Rom rom, BufferedInputStream in) throws IOException {
        in.mark(Integer.MAX_VALUE);
        String hash = Utils.calculateChecksumStream(in, "SHA-256");
        if(hash == null)
            return;

        in.reset();

        FileOutputStream out = null;
        try {
            File iconsDir = Rom.getIconsDir();
            if(iconsDir == null) {
                Log.e(TAG, "Failed to find icons directory!");
                return;
            }
            File dest = new File(iconsDir, hash + ".png");
            out = new FileOutputStream(dest);

            Bitmap b = Utils.resizeBitmap(BitmapFactory.decodeStream(in), 128, 128);
            b.compress(Bitmap.CompressFormat.PNG, 0, out);

            storeRomIcon(rom, R.id.user_defined_icon, hash);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } finally {
            Utils.close(out);
        }
    }

    public void setRomIcon(Rom rom, int drawableId) {
        storeRomIcon(rom, drawableId, null);
    }

    private void storeRomIcon(Rom rom, int icon_id, String hash) {
        String name = rom.name;
        if(rom.type == Rom.ROM_PRIMARY)
            name = INTERNAL_ROM;

        String data, ic_type;
        if(icon_id == R.id.user_defined_icon) {
            data = hash;
            ic_type = "user_defined";
        } else {
            Resources res = MgrApp.getAppContext().getResources();
            data = res.getResourceName(icon_id);
            data = MgrApp.replaceDebugPkgName(data, true);
            ic_type = "predef_set";

            final String mrom_ic_file = data.substring(data.lastIndexOf('/')+1) + ".png";
            if(m_predefIcons != null && !m_predefIcons.contains(mrom_ic_file)) {
                BufferedInputStream in = null;
                try {
                    in = new BufferedInputStream(res.openRawResource(icon_id));
                    setRomIcon(rom, in);
                } catch(Exception e) {
                    e.printStackTrace();
                } finally {
                    Utils.close(in);
                }

                return;
            }
        }

        Shell.SU.run(
                "cd '%s' && " +
                        "echo '%s' > .icon_data &&" +
                        "echo '%s' >> .icon_data"
                , rom.base_path, ic_type, data);

        rom.icon_id = icon_id;
        rom.icon_hash = hash;
        rom.resetIconDrawable();

        ContentValues val = new ContentValues();
        if(rom.icon_id == R.id.user_defined_icon)
            val.put(RomListOpenHelper.KEY_ICON_NAME, rom.icon_hash);
        else {
            String resName = MgrApp.getAppContext().getResources().getResourceName(rom.icon_id);
            val.put(RomListOpenHelper.KEY_ICON_NAME, resName);
        }

        MgrApp.getCntnResolver().update(RomListDataProvider.CONTENT_URI, val,
                RomListOpenHelper.KEY_NAME + "='" + rom.name + "' AND " +
                RomListOpenHelper.KEY_TYPE + "=" + rom.type  + " AND " +
                RomListOpenHelper.KEY_ACTIVE + "=" + rom.active  + " AND " +
                RomListOpenHelper.KEY_BASE_PATH + "='" + rom.base_path + "' AND " +
                RomListOpenHelper.KEY_ICON_PATH + "='" + rom.icon_path + "' AND " +
                RomListOpenHelper.KEY_PARTITION_NAME + "='" + rom.partition_name + "' AND " +
                RomListOpenHelper.KEY_PARTITION_MOUNT_PATH + "='" + rom.partition_mount_path + "' AND " +
                RomListOpenHelper.KEY_PARTITION_UUID + "='" + rom.partition_uuid + "' AND " +
                RomListOpenHelper.KEY_PARTITION_FS + "='" + rom.partition_fs + "' AND " +
                RomListOpenHelper.KEY_PARTITION_INFO + "='" + rom.partition_info  + "'",
                null);
        RomListWidgetProvider.notifyChanged();
    }

    public String getVersion() {
        return m_version;
    }
    public int get_apkL_version() {
        return m_apkL_version;
    }
    public boolean no_kexec() {
        if (!m_no_kexec)
            findInternalRomName(); // in case it's not setup, check again
        return m_no_kexec;
    }
    public String getPath() { return m_path; }
    public ArrayList<Rom> getRoms() { return m_roms; }

    private String m_path;
    private String m_version;
    private int m_apkL_version;
    private boolean m_no_kexec;
    private ArrayList<Rom> m_roms = new ArrayList<Rom>();
    private List<String> m_predefIcons;
}
