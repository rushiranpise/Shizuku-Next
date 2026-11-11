package rikka.shizuku.server;

import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

import java.util.ArrayList;

/**
 * One row of the manager's app list: the package, its persisted permission flags, and the
 * temporary grant/resources the server is holding for its uid.
 *
 * <p>This is the reply of the existing {@code getApplications} transaction. The manager and the
 * server are the same APK, and an old running server is restarted before the temporary view is
 * used, so the reply can carry the extra fields without a second call.
 */
public class ManagerAppEntry implements Parcelable {

    /** Null for a uid whose package is gone but whose privileged resources are still running. */
    @Nullable
    public final PackageInfo info;
    public final int uid;
    /** {@link ConfigManager#MASK_PERMISSION} bits from the persisted config. */
    public final int flags;
    public final boolean temporary;
    public final long since;
    public final int shells;
    public final int services;
    public final int hosts;
    /** One bundle per client process holding a temporary grant: pid + since. */
    public final ArrayList<Bundle> sessions;
    /** One bundle per privileged resource: kind + pid + session pid + label. */
    public final ArrayList<Bundle> entries;

    public ManagerAppEntry(@Nullable PackageInfo info, int uid, int flags, boolean temporary,
                           long since, int shells, int services, int hosts,
                           ArrayList<Bundle> sessions, ArrayList<Bundle> entries) {
        this.info = info;
        this.uid = uid;
        this.flags = flags;
        this.temporary = temporary;
        this.since = since;
        this.shells = shells;
        this.services = services;
        this.hosts = hosts;
        this.sessions = sessions;
        this.entries = entries;
    }

    protected ManagerAppEntry(Parcel in) {
        info = in.readParcelable(PackageInfo.class.getClassLoader());
        uid = in.readInt();
        flags = in.readInt();
        temporary = in.readInt() != 0;
        since = in.readLong();
        shells = in.readInt();
        services = in.readInt();
        hosts = in.readInt();
        sessions = in.createTypedArrayList(Bundle.CREATOR);
        entries = in.createTypedArrayList(Bundle.CREATOR);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(info, flags);
        dest.writeInt(uid);
        dest.writeInt(this.flags);
        dest.writeInt(temporary ? 1 : 0);
        dest.writeLong(since);
        dest.writeInt(shells);
        dest.writeInt(services);
        dest.writeInt(hosts);
        dest.writeTypedList(sessions);
        dest.writeTypedList(entries);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ManagerAppEntry> CREATOR = new Creator<ManagerAppEntry>() {
        @Override
        public ManagerAppEntry createFromParcel(Parcel in) {
            return new ManagerAppEntry(in);
        }

        @Override
        public ManagerAppEntry[] newArray(int size) {
            return new ManagerAppEntry[size];
        }
    };
}
