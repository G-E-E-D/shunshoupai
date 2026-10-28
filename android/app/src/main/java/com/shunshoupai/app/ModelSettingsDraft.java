package com.shunshoupai.app;

import androidx.lifecycle.ViewModel;

/** Unsaved API keys survive rotation in memory only, never in an Activity Bundle or preferences. */
public final class ModelSettingsDraft extends ViewModel {
    boolean loaded,allowPrivate;
    int version,editRevision;
    String baseUrl="",model="",apiKey="";
    public ModelSettingsDraft(){}
    @Override protected void onCleared(){apiKey="";}
}
