package com.nordfjell.nordqueuenotice;
public final class ReadOnlyPreflight {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected config path");
        var settings=NoticeSettings.load(java.nio.file.Path.of(args[0]),false);
        System.out.println("NOTICE_PREFLIGHT_OK interval="+settings.intervalSeconds()+" initialDelay="+settings.initialDelayMillis()+" clearLines="+settings.clearLines()+" JSONBytes="+settings.serializedBytes());
    }
}
