# L7：hook 侧不参与混淆；入口类名必须与 META-INF/xposed/java_init.list 一致。
-keep,allowoptimization class com.luckyme.watchcat.xposed.WatchCatModule {
    public <init>();
}
-adaptresourcefilecontents META-INF/xposed/java_init.list
-dontwarn io.github.libxposed.annotation.**
