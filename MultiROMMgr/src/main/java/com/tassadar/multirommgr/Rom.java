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

import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.File;
import java.util.Comparator;

public class Rom implements Parcelable {
    public static final int ROM_PRIMARY   = 0;
    public static final int ROM_SECONDARY = 1;

    public static class NameComparator implements Comparator<Rom> {
        @Override
        public int compare(Rom a, Rom b) {
            if (a.active != b.active) {
                if (a.active == 1)
                    return -1;

                if (b.active == 1)
                    return 1;
            }

            if(a.type == Rom.ROM_PRIMARY)
                return -1;

            if(b.type == Rom.ROM_PRIMARY)
                return 1;

            if (a.partition_mount_path.equals(b.partition_mount_path))
                return a.name.compareToIgnoreCase(b.name);
            else
                return a.partition_mount_path.compareTo(b.partition_mount_path);
        }
    }

    public static final Parcelable.Creator<Rom> CREATOR
            = new Parcelable.Creator<Rom>() {
        public Rom createFromParcel(Parcel in) {
            return new Rom(in);
        }

        public Rom[] newArray(int size) {
            return new Rom[size];
        }
    };

    public static File getIconsDir() {
        try {
            String path = MgrApp.getAppContext().getExternalFilesDir(null).getAbsolutePath();
            path = MgrApp.replaceDebugPkgName(path, true);

            File f_path = new File(path);
            if(!f_path.exists())
                f_path.mkdirs();

            return f_path;
        } catch(NullPointerException ex) {
            ex.printStackTrace();
            return null;
        }
    }

/*
    public Rom(String name, int type) {
        this.name = name;
        this.type = type;
        this.icon_id = R.drawable.romic_default;
        this.icon_hash = null;

        this.base_path = "";
        this.icon_path = "";
        this.partition_name = "";
        this.partition_mount_path = "";
        this.partition_uuid = "";
        this.partition_fs = "";
        this.partition_info = "";
    }
*/
    /*
    public Rom(String name, int type) {
        new Rom(name, type, "", "", "", "", "", "");
                if (type == Rom.ROM_PRIMARY)
                    base_path = m_path + "roms/" + INTERNAL_ROM;
                else
                    base_path = m_path + "roms/" + name;
    }*/

    public Rom(String name, int type,
               int active,
               String base_path, String icon_path,
               String partition_name, String partition_mount_path, String partition_uuid, String partition_fs) {
        this.name = name;
        this.type = type;
        this.icon_id = R.drawable.romic_default;
        this.icon_hash = null;

        this.active = active;
        this.base_path = base_path;
        this.icon_path = icon_path;
        this.partition_name = partition_name;
        this.partition_mount_path = partition_mount_path;
        this.partition_uuid = partition_uuid;
        this.partition_fs = partition_fs;

        if (this.type == ROM_PRIMARY)
            this.partition_info = "Primary ROM";
        else if (this.partition_name.isEmpty())
            this.partition_info = "Internal Storage";
        else
            this.partition_info = this.partition_name + " (" + this.partition_fs + ")";

//        if (this.name == && this.base_path == base_path)
//            this.active = 1;
//        else
//            this.active = 0;
    }

    public Rom(Parcel in) {
        this.name = in.readString();
        this.type = in.readInt();
        this.icon_id = in.readInt();
        this.icon_hash = (String)in.readValue(String.class.getClassLoader());

        this.active = in.readInt();
        this.base_path = in.readString();
        this.icon_path = in.readString();
        this.partition_name = in.readString();
        this.partition_mount_path = in.readString();
        this.partition_uuid = in.readString();
        this.partition_fs = in.readString();

        if (this.type == ROM_PRIMARY)
            this.partition_info = "Primary ROM";
        else if (this.partition_name.isEmpty())
            this.partition_info = "Internal Storage";
        else
            this.partition_info = this.partition_name + " (" + this.partition_fs + ")";
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.name);
        dest.writeInt(this.type);
        dest.writeInt(this.icon_id);
        dest.writeValue(this.icon_hash);

        dest.writeInt(this.active);
        dest.writeString(this.base_path);
        dest.writeString(this.icon_path);
        dest.writeString(this.partition_name);
        dest.writeString(this.partition_mount_path);
        dest.writeString(this.partition_uuid);
        dest.writeString(this.partition_fs);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public Drawable getIcon() {
        if(m_icon == null)
            m_icon = loadIcon();
        return m_icon;
    }

    private Drawable loadIcon() {
        Drawable res = null;
        Resources r = MgrApp.getAppContext().getResources();

        if(this.icon_id == R.id.user_defined_icon) {
            File path = new File(getIconsDir(), this.icon_hash + ".png");
            res = Drawable.createFromPath(path.getAbsolutePath());
        } else {
            try {
                res = r.getDrawable(this.icon_id);
            } catch(Resources.NotFoundException e) {
                // expected
            }
        }

        if(res == null) {
            res = r.getDrawable(R.drawable.romic_default);
            this.icon_id = R.drawable.romic_default;
            this.icon_hash = null;
        }

        return res;
    }

    public void resetIconDrawable() {
        m_icon = null;
    }

    public String name;
    public int type;
    public int icon_id;
    public String icon_hash;

    public int active;
    public String base_path;
    public String icon_path;
    public String partition_name;
    public String partition_mount_path;
    public String partition_uuid;
    public String partition_fs;
    public String partition_info; // this is used to for display (eg "Internal Storage" or "mmcblk1p2 (ext4)")

    private Drawable m_icon;
}
