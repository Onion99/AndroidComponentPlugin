@file:Suppress("DEPRECATION")

package com.malin.plugin.impl

import android.os.Build
import dalvik.system.DexFile
import dalvik.system.PathClassLoader
import java.io.File
import java.util.zip.ZipFile


/**
 * 由于应用程序使用的ClassLoader为PathClassLoader 最终继承自 BaseDexClassLoader
 * 查看源码得知,这个BaseDexClassLoader加载代码根据一个叫做dexElements的数组进行,
 * 因此我们把包含代码的dex文件插入这个数组. 系统的classLoader就能帮助我们找到这个类
 *
 *
 * 把插件的相关信息放入BaseDexClassLoader的表示dex文件的数组里面,
 * 这样宿主程序的ClassLoader在进行类加载,遍历这个数组的时候,
 * 会自动遍历到我们添加进去的插件信息,从而完成插件类的加载！
 *
 *
 * 这个类用来进行对于BaseDexClassLoader的Hook
 * com from wei shu
 * http://weishu.me/2016/04/05/understand-plugin-framework-classloader/
 */
object BaseDexClassLoaderHookHelper {

    /**
     * 使用宿主ClassLoader帮助加载插件类
     *
     * 原理:
     * 默认情况下performLaunchActivity会使用替身StubActivity的ApplicationInfo也就是宿主程序的ClassLoader加载所有的类;
     * 我们的思路是告诉宿主ClassLoader我们在哪,让其帮助完成类加载的过程.
     * 宿主程序的ClassLoader最终继承自BaseDexClassLoader,BaseDexClassLoader通过DexPathList进行类的查找过程;
     * 而这个查找通过遍历一个dexElements的数组完成;
     * 我们通过把插件dex添加进这个数组就让宿主ClassLoader获取了加载插件类的能力.
     * 系统使用ClassLoader findClass的过程,发现应用程序使用的非系统类都是通过同一个PathClassLoader加载的;
     * 而这个类的最终父类BaseDexClassLoader通过DexPathList完成类的查找过程;我们hack了这个查找过程,从而完成了插件类的加载
     *
     * @param baseDexClassLoader 表示宿主的LoadedApk在Application类中有一个成员变量mLoadedApk,而这个变量是从ContextImpl中获取的;
     * ContextImpl重写了getClassLoader方法,
     * 因此我们在Context环境中直接getClassLoader()获取到的就是宿主程序唯一的ClassLoader.
     * @param apkFile            apkFile
     * @param optDexFile         optDexFile
     */
    fun patchClassLoader(baseDexClassLoader: ClassLoader, apkFile: File, optDexFile: File) {

        // -->PathClassLoader
        // -->BaseDexClassLoader
        // -->BaseDexClassLoader中DexPathList pathList
        // -->DexPathList中 Element[] dexElements
        try {

            // https://developer.android.com/about/versions/14/behavior-changes-14?hl=zh-cn#safer-dynamic-code-loading
            if (Build.VERSION.SDK_INT >= 34) {//android14
                try {
                    apkFile.setReadOnly()
                } catch (ignore: Throwable) {
                }
            }

            // 0.获取PathClassLoader的父类dalvik.system.BaseDexClassLoader的Class对象
            val baseDexClassLoaderClazz = PathClassLoader::class.java.superclass

            // 1.获取BaseDexClassLoader的成员DexPathList pathList
            // private final DexPathList pathList;
            // http://androidxref.com/9.0.0_r3/xref/libcore/dalvik/src/main/java/dalvik/system/BaseDexClassLoader.java
            // 2.获取DexPathList pathList实例;
            val dexPathList = baseDexClassLoaderClazz.getDeclaredField("pathList")
                .also { it.isAccessible = true }[baseDexClassLoader]

            // 3.获取DexPathList的成员: Element[] dexElements 的Field
            // private Element[] dexElements;
            // http://androidxref.com/9.0.0_r3/xref/libcore/dalvik/src/main/java/dalvik/system/DexPathList.java
            val dexElementsField = dexPathList.javaClass.getDeclaredField("dexElements")
                .also { it.isAccessible = true }

            // 4.获取DexPathList的成员 Element[] dexElements 的值
            // Element是DexPathList的内部类
            val dexElements = dexElementsField[dexPathList] as Array<*>

            // 5.获取dexElements数组的类型 (Element)
            // 数组的 class 对象的getComponentType()方法可以取得一个数组的Class对象
            val elementClazz = dexElements.javaClass.componentType

            // 6.创建一个数组, 用来替换原始的数组
            // 通过Array.newInstance()可以反射生成数组对象, 需要指定元素类型和数组长度
            val hostAndPluginElements = java.lang.reflect.Array.newInstance(
                elementClazz!!,
                dexElements.size + 1
            ) as Array<*>

            // 根据不同的API, 获取插件DexClassLoader的 DexPathList中的 dexElements数组
            val apiLevel = Build.VERSION.SDK_INT

            // http://androidxref.com/9.0.0_r3/xref/libcore/dalvik/src/main/java/dalvik/system/DexFile.java#160
            // DexFile.loadDex(String sourcePathName,String outputPathName,int flag){}
            //  @param sourcePathName Jar or APK file with "classes.dex".  (May expand this to include "raw DEX" in the future.)
            //  @param outputPathName File that will hold the optimized form of the DEX data.
            //  @param flags Enable optional features.  (Currently none defined.)
            // warn log from http://androidxref.com/9.0.0_r3/xref/art/runtime/oat_file_manager.cc#404
            val dexFile =
                DexFile.loadDex(apkFile.canonicalPath, optDexFile.canonicalPath, 0)

            val elementPluginObj: Any = when {
                apiLevel >= 26 -> {
                    // 26<=API<=31 (8.0<=API<=12.0)
                    // 7.构造插件Element
                    // 使用构造函数 public Element(DexFile dexFile, File dexZipPath){}
                    // 这个构造函数不能用了 @Deprecated public Element(File dir, boolean isDirectory, File zip, DexFile dexFile){},使用会报错
                    // http://androidxref.com/9.0.0_r3/xref/libcore/dalvik/src/main/java/dalvik/system/DexPathList.java#646
                    // 注意getConstructor vs getDeclaredConstructor 的区别

                    // 8. 生成Element的实例对象
                    // http://androidxref.com/9.0.0_r3/xref/libcore/dalvik/src/main/java/dalvik/system/DexPathList.java#606
                    elementClazz.getDeclaredConstructor(
                        DexFile::class.java, File::class.java
                    ).also { it.isAccessible = true }.newInstance(dexFile, apkFile)
                }

                apiLevel >= 18 -> {
                    // 18<=API<=25 (4.3<=API<=7.1.1)
                    // 7.构造插件Element
                    // 使用构造函数 public Element(File file, boolean isDirectory, File zip, DexFile dexFile){}
                    // 8. 生成Element的实例对象
                    // http://androidxref.com/4.3_r2.1/xref/libcore/dalvik/src/main/java/dalvik/system/DexPathList.java#383
                    elementClazz.getDeclaredConstructor(
                        File::class.java,
                        Boolean::class.javaPrimitiveType,
                        File::class.java,
                        DexFile::class.java
                    ).also { it.isAccessible = true }.newInstance(apkFile, false, apkFile, dexFile)
                }

                apiLevel == 17 -> {
                    // API=17  (API=4.2)
                    // 7.构造插件Element
                    // 使用构造函数:public Element(File file, File zip, DexFile dexFile){}
                    // 8. 生成Element的实例对象
                    // http://androidxref.com/4.2_r1/xref/libcore/dalvik/src/main/java/dalvik/system/DexPathList.java#387
                    elementClazz.getDeclaredConstructor(
                        File::class.java, File::class.java, DexFile::class.java
                    ).also { it.isAccessible = true }.newInstance(apkFile, apkFile, dexFile)
                }

                else -> {
                    // 15~16 (4.0.3=<API=4.1)
                    // 7.构造插件Element
                    // 使用构造函数:public Element(File file, ZipFile zipFile, DexFile dexFile){}
                    // 8. 生成Element的实例对象
                    // http://androidxref.com/4.1.1/xref/libcore/dalvik/src/main/java/dalvik/system/DexPathList.java#387
                    // http://androidxref.com/4.0.3_r1/xref/libcore/dalvik/src/main/java/dalvik/system/DexPathList.java#387
                    elementClazz.getDeclaredConstructor(
                        File::class.java, ZipFile::class.java, DexFile::class.java
                    ).also { it.isAccessible = true }
                        .newInstance(apkFile, ZipFile(apkFile), dexFile)
                }
            }

            // 9.创建插件element数组
            val pluginElements = arrayOf(elementPluginObj)

            // public static native void arraycopy(Object src,  int  srcPos, Object dest, int destPos, int length)
            // * @param      src      the source array.
            // * @param      srcPos   starting position in the source array.
            // * @param      dest     the destination array.
            // * @param      destPos  starting position in the destination data.
            // * @param      length   the number of array elements to be copied.
            // https://blog.csdn.net/wenzhi20102321/article/details/78444158

            // 10.把宿主的elements复制进去
            System.arraycopy(dexElements, 0, hostAndPluginElements, 0, dexElements.size)

            // 11.把宿主的elements复制进去
            System.arraycopy(
                pluginElements,
                0,
                hostAndPluginElements,
                dexElements.size,
                pluginElements.size
            )

            // 12.替换
            dexElementsField[dexPathList] = hostAndPluginElements

            // 简要总结一下这种方式的原理:
            // 默认情况下performLaunchActivity会使用替身StubActivity的ApplicationInfo也就是宿主程序的ClassLoader加载所有的类;
            // 我们的思路是告诉宿主ClassLoader我们在哪,让其帮助完成类加载的过程.
            // 宿主程序的ClassLoader最终继承自BaseDexClassLoader,BaseDexClassLoader通过DexPathList进行类的查找过程;
            // 而这个查找通过遍历一个dexElements的数组完成;
            // 我们通过把插件dex添加进这个数组就让宿主ClassLoader获取了加载插件类的能力.
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
