package com.jaus.albertogiunta.justintrain_oraritreni.db;

import android.arch.persistence.room.ColumnInfo;
import android.arch.persistence.room.Entity;
import android.arch.persistence.room.PrimaryKey;

@Entity(tableName = "station")
public class Station {

    @PrimaryKey
    private Integer sid;

    @ColumnInfo(name = "name_short")
    private String  nameShort;

    @ColumnInfo(name = "name_long")
    private String  nameLong;

    @ColumnInfo(name = "name_fancy")
    private String nameFancy;

    @ColumnInfo(name = "id_short")
    private String  stationShortId;

    @ColumnInfo(name = "id_long")
    private String  stationLongId;

    public Integer getSid() {
        return sid;
    }

    public void setSid(Integer sid) {
        this.sid = sid;
    }

    public String getStationShortId() {
        return stationShortId;
    }

    public void setStationShortId(String stationShortId) {
        this.stationShortId = stationShortId;
    }

    public String getStationLongId() {
        return stationLongId;
    }

    public void setStationLongId(String stationLongId) {
        this.stationLongId = stationLongId;
    }

    public String getNameFancy() {
        return nameFancy;
    }

    public void setNameFancy(String nameFancy) {
        this.nameFancy = nameFancy;
    }

    public String getNameShort() {
        return nameShort;
    }

    public void setNameShort(String nameShort) {
        this.nameShort = nameShort;
    }

    public String getNameLong() {
        return nameLong;
    }

    public void setNameLong(String nameLong) {
        this.nameLong = nameLong;
    }

    @Override
    public String toString() {
        return "Station{" +
                "stationShortId='" + stationShortId + '\'' +
                ", stationLongId='" + stationLongId + '\'' +
                ", nameLong='" + nameLong + '\'' +
                ", nameShort='" + nameShort + '\'' +
                '}';
    }
}
