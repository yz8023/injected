package com.example.injector.core;

import android.content.Context;
import android.util.Base64;

import org.jf.smali.Smali;
import org.jf.smali.SmaliOptions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 内置弹窗库。
 *
 * 弹窗类以 smali 源码形式内嵌，选择后用工程自带的 Smali.assemble 现场汇编出
 * 极小的 classes.dex，并打包为标准弹窗包（classes.dex + xymods.txt + assets/）。
 * 产出的 zip 可直接用于注入页与预览页。
 */
public class BuiltinPopups {

    public interface LogFn {
        void log(String line);
    }

    public static class Def {
        public final String name;
        public final String[] smaliFiles;
        public final String pngBase64;

        public Def(String name, String[] smaliFiles, String pngBase64) {
            this.name = name;
            this.smaliFiles = smaliFiles;
            this.pngBase64 = pngBase64;
        }
    }

    private static final Pattern CLASS_LINE =
            Pattern.compile("^\\.class\\s+[^\\n]*?L([\\w$/]+);\\s*$", Pattern.MULTILINE);

    // ============================ smali 源码 ============================

    private static final String S_HELLO =
        ".class public Lcom/example/injector/popup/HelloPopup;\n"
        + ".super Ljava/lang/Object;\n"
        + "\n"
        + ".method public static show(Landroid/content/Context;)V\n"
        + "    .locals 3\n"
        + "\n"
        + "    new-instance v0, Landroid/app/AlertDialog$Builder;\n"
        + "    invoke-direct {v0, p0}, Landroid/app/AlertDialog$Builder;-><init>(Landroid/content/Context;)V\n"
        + "\n"
        + "    const-string v1, \"@@TITLE@@\"\n"
        + "    invoke-virtual {v0, v1}, Landroid/app/AlertDialog$Builder;->setTitle(Ljava/lang/CharSequence;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v0\n"
        + "\n"
        + "    const-string v1, \"@@CONTENT@@\"\n"
        + "    invoke-virtual {v0, v1}, Landroid/app/AlertDialog$Builder;->setMessage(Ljava/lang/CharSequence;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v0\n"
        + "\n"
        + "    const-string v1, \"@@BTN1@@\"\n"
        + "    const/4 v2, 0x0\n"
        + "    invoke-virtual {v0, v1, v2}, Landroid/app/AlertDialog$Builder;->setPositiveButton(Ljava/lang/CharSequence;Landroid/content/DialogInterface$OnClickListener;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v0\n"
        + "\n"
        + "    const/4 v1, @@CANCELABLE@@\n"
        + "    invoke-virtual {v0, v1}, Landroid/app/AlertDialog$Builder;->setCancelable(Z)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v0\n"
        + "\n"
        + "    invoke-virtual {v0}, Landroid/app/AlertDialog$Builder;->show()Landroid/app/AlertDialog;\n"
        + "\n"
        + "    return-void\n"
        + ".end method\n";

    private static final String S_MULTI =
        ".class public Lcom/example/injector/popup/MultiButtonPopup;\n"
        + ".super Ljava/lang/Object;\n"
        + "\n"
        + ".method public static show(Landroid/content/Context;)V\n"
        + "    .locals 3\n"
        + "\n"
        + "    new-instance v0, Landroid/app/AlertDialog$Builder;\n"
        + "    invoke-direct {v0, p0}, Landroid/app/AlertDialog$Builder;-><init>(Landroid/content/Context;)V\n"
        + "\n"
        + "    const-string v1, \"@@TITLE@@\"\n"
        + "    invoke-virtual {v0, v1}, Landroid/app/AlertDialog$Builder;->setTitle(Ljava/lang/CharSequence;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v0\n"
        + "\n"
        + "    const-string v1, \"@@CONTENT@@\"\n"
        + "    invoke-virtual {v0, v1}, Landroid/app/AlertDialog$Builder;->setMessage(Ljava/lang/CharSequence;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v0\n"
        + "\n"
        + "    const-string v1, \"@@BTN1@@\"\n"
        + "    const/4 v2, 0x0\n"
        + "    invoke-virtual {v0, v1, v2}, Landroid/app/AlertDialog$Builder;->setPositiveButton(Ljava/lang/CharSequence;Landroid/content/DialogInterface$OnClickListener;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v0\n"
        + "\n"
        + "    const-string v1, \"@@BTN2@@\"\n"
        + "    const/4 v2, 0x0\n"
        + "    invoke-virtual {v0, v1, v2}, Landroid/app/AlertDialog$Builder;->setNegativeButton(Ljava/lang/CharSequence;Landroid/content/DialogInterface$OnClickListener;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v0\n"
        + "\n"
        + "    const-string v1, \"@@BTN3@@\"\n"
        + "    const/4 v2, 0x0\n"
        + "    invoke-virtual {v0, v1, v2}, Landroid/app/AlertDialog$Builder;->setNeutralButton(Ljava/lang/CharSequence;Landroid/content/DialogInterface$OnClickListener;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v0\n"
        + "\n"
        + "    const/4 v1, @@CANCELABLE@@\n"
        + "    invoke-virtual {v0, v1}, Landroid/app/AlertDialog$Builder;->setCancelable(Z)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v0\n"
        + "\n"
        + "    invoke-virtual {v0}, Landroid/app/AlertDialog$Builder;->show()Landroid/app/AlertDialog;\n"
        + "\n"
        + "    return-void\n"
        + ".end method\n";

    private static final String S_IMAGE =
        ".class public Lcom/example/injector/popup/ImagePopup;\n"
        + ".super Ljava/lang/Object;\n"
        + "\n"
        + ".method public static show(Landroid/content/Context;)V\n"
        + "    .locals 6\n"
        + "\n"
        + "    invoke-virtual {p0}, Landroid/content/Context;->getCacheDir()Ljava/io/File;\n"
        + "    move-result-object v0\n"
        + "\n"
        + "    new-instance v1, Ljava/io/File;\n"
        + "    const-string v2, \"popup_assets/xypopups/banner.png\"\n"
        + "    invoke-direct {v1, v0, v2}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V\n"
        + "\n"
        + "    invoke-virtual {v1}, Ljava/io/File;->exists()Z\n"
        + "    move-result v2\n"
        + "    if-eqz v2, :cond_assets\n"
        + "\n"
        + "    new-instance v3, Ljava/io/FileInputStream;\n"
        + "    invoke-direct {v3, v1}, Ljava/io/FileInputStream;-><init>(Ljava/io/File;)V\n"
        + "    invoke-static {v3}, Landroid/graphics/BitmapFactory;->decodeStream(Ljava/io/InputStream;)Landroid/graphics/Bitmap;\n"
        + "    move-result-object v0\n"
        + "    goto/32 :goto_build\n"
        + "\n"
        + "    :cond_assets\n"
        + "    invoke-virtual {p0}, Landroid/content/Context;->getAssets()Landroid/content/res/AssetManager;\n"
        + "    move-result-object v3\n"
        + "    const-string v4, \"xypopups/banner.png\"\n"
        + "    invoke-virtual {v3, v4}, Landroid/content/res/AssetManager;->open(Ljava/lang/String;)Ljava/io/InputStream;\n"
        + "    move-result-object v3\n"
        + "    invoke-static {v3}, Landroid/graphics/BitmapFactory;->decodeStream(Ljava/io/InputStream;)Landroid/graphics/Bitmap;\n"
        + "    move-result-object v0\n"
        + "\n"
        + "    :goto_build\n"
        + "    new-instance v3, Landroid/app/AlertDialog$Builder;\n"
        + "    invoke-direct {v3, p0}, Landroid/app/AlertDialog$Builder;-><init>(Landroid/content/Context;)V\n"
        + "\n"
        + "    const-string v4, \"@@TITLE@@\"\n"
        + "    invoke-virtual {v3, v4}, Landroid/app/AlertDialog$Builder;->setTitle(Ljava/lang/CharSequence;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v3\n"
        + "\n"
        + "    if-eqz v0, :cond_noimg\n"
        + "\n"
        + "    new-instance v4, Landroid/widget/ImageView;\n"
        + "    invoke-direct {v4, p0}, Landroid/widget/ImageView;-><init>(Landroid/content/Context;)V\n"
        + "    invoke-virtual {v4, v0}, Landroid/widget/ImageView;->setImageBitmap(Landroid/graphics/Bitmap;)V\n"
        + "    invoke-virtual {v3, v4}, Landroid/app/AlertDialog$Builder;->setView(Landroid/view/View;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v3\n"
        + "\n"
        + "    :cond_noimg\n"
        + "    const-string v4, \"@@CONTENT@@\"\n"
        + "    invoke-virtual {v3, v4}, Landroid/app/AlertDialog$Builder;->setMessage(Ljava/lang/CharSequence;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v3\n"
        + "\n"
        + "    const-string v4, \"@@BTN1@@\"\n"
        + "    const/4 v5, 0x0\n"
        + "    invoke-virtual {v3, v4, v5}, Landroid/app/AlertDialog$Builder;->setPositiveButton(Ljava/lang/CharSequence;Landroid/content/DialogInterface$OnClickListener;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v3\n"
        + "\n"
        + "    const/4 v4, @@CANCELABLE@@\n"
        + "    invoke-virtual {v3, v4}, Landroid/app/AlertDialog$Builder;->setCancelable(Z)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v3\n"
        + "\n"
        + "    invoke-virtual {v3}, Landroid/app/AlertDialog$Builder;->show()Landroid/app/AlertDialog;\n"
        + "\n"
        + "    return-void\n"
        + ".end method\n";

    private static final String S_INPUT =
        ".class public Lcom/example/injector/popup/InputPopup;\n"
        + ".super Ljava/lang/Object;\n"
        + "\n"
        + ".method public static show(Landroid/content/Context;)V\n"
        + "    .locals 4\n"
        + "\n"
        + "    new-instance v0, Landroid/widget/EditText;\n"
        + "    invoke-direct {v0, p0}, Landroid/widget/EditText;-><init>(Landroid/content/Context;)V\n"
        + "\n"
        + "    const-string v1, \"@@HINT@@\"\n"
        + "    invoke-virtual {v0, v1}, Landroid/widget/EditText;->setHint(Ljava/lang/CharSequence;)V\n"
        + "\n"
        + "    new-instance v1, Landroid/app/AlertDialog$Builder;\n"
        + "    invoke-direct {v1, p0}, Landroid/app/AlertDialog$Builder;-><init>(Landroid/content/Context;)V\n"
        + "\n"
        + "    const-string v2, \"@@TITLE@@\"\n"
        + "    invoke-virtual {v1, v2}, Landroid/app/AlertDialog$Builder;->setTitle(Ljava/lang/CharSequence;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    invoke-virtual {v1, v0}, Landroid/app/AlertDialog$Builder;->setView(Landroid/view/View;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    new-instance v2, Lcom/example/injector/popup/InputPopup$1;\n"
        + "    invoke-direct {v2, v0, p0}, Lcom/example/injector/popup/InputPopup$1;-><init>(Landroid/widget/EditText;Landroid/content/Context;)V\n"
        + "\n"
        + "    const-string v3, \"@@BTN1@@\"\n"
        + "    invoke-virtual {v1, v3, v2}, Landroid/app/AlertDialog$Builder;->setPositiveButton(Ljava/lang/CharSequence;Landroid/content/DialogInterface$OnClickListener;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    const/4 v3, @@CANCELABLE@@\n"
        + "    invoke-virtual {v1, v3}, Landroid/app/AlertDialog$Builder;->setCancelable(Z)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    invoke-virtual {v1}, Landroid/app/AlertDialog$Builder;->show()Landroid/app/AlertDialog;\n"
        + "\n"
        + "    return-void\n"
        + ".end method\n";

    private static final String S_INPUT_LISTENER =
        ".class public Lcom/example/injector/popup/InputPopup$1;\n"
        + ".super Ljava/lang/Object;\n"
        + ".implements Landroid/content/DialogInterface$OnClickListener;\n"
        + "\n"
        + ".field private final val$ctx:Landroid/content/Context;\n"
        + ".field private final val$et:Landroid/widget/EditText;\n"
        + "\n"
        + ".method public constructor <init>(Landroid/widget/EditText;Landroid/content/Context;)V\n"
        + "    .registers 3\n"
        + "\n"
        + "    invoke-direct {p0}, Ljava/lang/Object;-><init>()V\n"
        + "\n"
        + "    iput-object p1, p0, Lcom/example/injector/popup/InputPopup$1;->val$et:Landroid/widget/EditText;\n"
        + "    iput-object p2, p0, Lcom/example/injector/popup/InputPopup$1;->val$ctx:Landroid/content/Context;\n"
        + "\n"
        + "    return-void\n"
        + ".end method\n"
        + "\n"
        + ".method public onClick(Landroid/content/DialogInterface;I)V\n"
        + "    .locals 4\n"
        + "\n"
        + "    iget-object v0, p0, Lcom/example/injector/popup/InputPopup$1;->val$et:Landroid/widget/EditText;\n"
        + "\n"
        + "    invoke-virtual {v0}, Landroid/widget/EditText;->getText()Landroid/text/Editable;\n"
        + "    move-result-object v0\n"
        + "\n"
        + "    invoke-virtual {v0}, Ljava/lang/Object;->toString()Ljava/lang/String;\n"
        + "    move-result-object v0\n"
        + "\n"
        + "    new-instance v1, Ljava/lang/StringBuilder;\n"
        + "    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V\n"
        + "\n"
        + "    const-string v2, \"@@ECHO@@\"\n"
        + "    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    iget-object v2, p0, Lcom/example/injector/popup/InputPopup$1;->val$ctx:Landroid/content/Context;\n"
        + "\n"
        + "    const/4 v3, 0x1\n"
        + "    invoke-static {v2, v1, v3}, Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    invoke-virtual {v1}, Landroid/widget/Toast;->show()V\n"
        + "\n"
        + "    return-void\n"
        + ".end method\n";

    private static final String S_UPDATE =
        ".class public Lcom/example/injector/popup/UpdatePopup;\n"
        + ".super Ljava/lang/Object;\n"
        + "\n"
        + ".method public static show(Landroid/content/Context;)V\n"
        + "    .locals 3\n"
        + "\n"
        + "    new-instance v0, Landroid/app/AlertDialog$Builder;\n"
        + "    invoke-direct {v0, p0}, Landroid/app/AlertDialog$Builder;-><init>(Landroid/content/Context;)V\n"
        + "\n"
        + "    const-string v1, \"@@TITLE@@\"\n"
        + "    invoke-virtual {v0, v1}, Landroid/app/AlertDialog$Builder;->setTitle(Ljava/lang/CharSequence;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v0\n"
        + "\n"
        + "    const-string v1, \"@@CONTENT@@\"\n"
        + "    invoke-virtual {v0, v1}, Landroid/app/AlertDialog$Builder;->setMessage(Ljava/lang/CharSequence;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v0\n"
        + "\n"
        + "    const-string v1, \"@@BTN1@@\"\n"
        + "@@BTN1_LISTENER@@\n"
        + "    invoke-virtual {v0, v1, v2}, Landroid/app/AlertDialog$Builder;->setPositiveButton(Ljava/lang/CharSequence;Landroid/content/DialogInterface$OnClickListener;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v0\n"
        + "\n"
        + "    const-string v1, \"@@BTN2@@\"\n"
        + "    const/4 v2, 0x0\n"
        + "    invoke-virtual {v0, v1, v2}, Landroid/app/AlertDialog$Builder;->setNegativeButton(Ljava/lang/CharSequence;Landroid/content/DialogInterface$OnClickListener;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v0\n"
        + "\n"
        + "    const/4 v1, @@CANCELABLE@@\n"
        + "    invoke-virtual {v0, v1}, Landroid/app/AlertDialog$Builder;->setCancelable(Z)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v0\n"
        + "\n"
        + "    invoke-virtual {v0}, Landroid/app/AlertDialog$Builder;->show()Landroid/app/AlertDialog;\n"
        + "\n"
        + "    return-void\n"
        + ".end method\n";

    private static final String S_UPDATE_LISTENER =
        ".class public Lcom/example/injector/popup/UpdatePopup$1;\n"
        + ".super Ljava/lang/Object;\n"
        + ".implements Landroid/content/DialogInterface$OnClickListener;\n"
        + "\n"
        + ".field private final val$ctx:Landroid/content/Context;\n"
        + ".field private final val$url:Ljava/lang/String;\n"
        + "\n"
        + ".method public constructor <init>(Landroid/content/Context;Ljava/lang/String;)V\n"
        + "    .registers 3\n"
        + "\n"
        + "    invoke-direct {p0}, Ljava/lang/Object;-><init>()V\n"
        + "\n"
        + "    iput-object p1, p0, Lcom/example/injector/popup/UpdatePopup$1;->val$ctx:Landroid/content/Context;\n"
        + "    iput-object p2, p0, Lcom/example/injector/popup/UpdatePopup$1;->val$url:Ljava/lang/String;\n"
        + "\n"
        + "    return-void\n"
        + ".end method\n"
        + "\n"
        + ".method public onClick(Landroid/content/DialogInterface;I)V\n"
        + "    .locals 4\n"
        + "\n"
        + "    :try_start_0\n"
        + "    new-instance v0, Landroid/content/Intent;\n"
        + "    const-string v1, \"android.intent.action.VIEW\"\n"
        + "    invoke-direct {v0, v1}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V\n"
        + "\n"
        + "    iget-object v1, p0, Lcom/example/injector/popup/UpdatePopup$1;->val$url:Ljava/lang/String;\n"
        + "    invoke-static {v1}, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    invoke-virtual {v0, v1}, Landroid/content/Intent;->setData(Landroid/net/Uri;)Landroid/content/Intent;\n"
        + "\n"
        + "    iget-object v1, p0, Lcom/example/injector/popup/UpdatePopup$1;->val$ctx:Landroid/content/Context;\n"
        + "    invoke-virtual {v1, v0}, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V\n"
        + "    :try_end_0\n"
        + "    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0\n"
        + "\n"
        + "    return-void\n"
        + "\n"
        + "    :catch_0\n"
        + "    move-exception v0\n"
        + "\n"
        + "    iget-object v1, p0, Lcom/example/injector/popup/UpdatePopup$1;->val$ctx:Landroid/content/Context;\n"
        + "    const-string v2, \"\\u6253\\u5f00\\u94fe\\u63a5\\u5931\\u8d25\"\n"
        + "    const/4 v3, 0x1\n"
        + "    invoke-static {v1, v2, v3}, Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    invoke-virtual {v1}, Landroid/widget/Toast;->show()V\n"
        + "\n"
        + "    return-void\n"
        + ".end method\n";

    private static final String S_ANIME =
        ".class public Lcom/example/injector/popup/AnimePopup;\n"
        + ".super Ljava/lang/Object;\n"
        + "\n"
        + ".method public static show(Landroid/content/Context;)V\n"
        + "    .locals 6\n"
        + "\n"
        + "    new-instance v0, Landroid/widget/LinearLayout;\n"
        + "    invoke-direct {v0, p0}, Landroid/widget/LinearLayout;-><init>(Landroid/content/Context;)V\n"
        + "\n"
        + "    const/4 v1, 0x1\n"
        + "    invoke-virtual {v0, v1}, Landroid/widget/LinearLayout;->setOrientation(I)V\n"
        + "\n"
        + "    const/16 v1, 0x11\n"
        + "    invoke-virtual {v0, v1}, Landroid/widget/LinearLayout;->setGravity(I)V\n"
        + "\n"
        + "    const/16 v1, 0x30\n"
        + "    invoke-virtual {v0, v1, v1, v1, v1}, Landroid/widget/LinearLayout;->setPadding(IIII)V\n"
        + "\n"
        + "    new-instance v1, Landroid/graphics/drawable/GradientDrawable;\n"
        + "    invoke-direct {v1}, Landroid/graphics/drawable/GradientDrawable;-><init>()V\n"
        + "\n"
        + "    const/4 v2, 0x2\n"
        + "    new-array v2, v2, [I\n"
        + "    const v3, -0x704f\n"
        + "    const/4 v4, 0x0\n"
        + "    aput v3, v2, v4\n"
        + "    const v3, -0x4c7701\n"
        + "    const/4 v4, 0x1\n"
        + "    aput v3, v2, v4\n"
        + "    invoke-virtual {v1, v2}, Landroid/graphics/drawable/GradientDrawable;->setColors([I)V\n"
        + "\n"
        + "    sget-object v2, Landroid/graphics/drawable/GradientDrawable$Orientation;->TL_BR:Landroid/graphics/drawable/GradientDrawable$Orientation;\n"
        + "    invoke-virtual {v1, v2}, Landroid/graphics/drawable/GradientDrawable;->setOrientation(Landroid/graphics/drawable/GradientDrawable$Orientation;)V\n"
        + "\n"
        + "    const/high16 v2, 0x42800000\n"
        + "    invoke-virtual {v1, v2}, Landroid/graphics/drawable/GradientDrawable;->setCornerRadius(F)V\n"
        + "\n"
        + "    invoke-virtual {v0, v1}, Landroid/widget/LinearLayout;->setBackground(Landroid/graphics/drawable/Drawable;)V\n"
        + "\n"
        + "    new-instance v1, Landroid/widget/TextView;\n"
        + "    invoke-direct {v1, p0}, Landroid/widget/TextView;-><init>(Landroid/content/Context;)V\n"
        + "\n"
        + "    const-string v2, \"@@TITLE@@\"\n"
        + "    invoke-virtual {v1, v2}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V\n"

        + "    const/high16 v2, 0x41c00000\n"
        + "    invoke-virtual {v1, v2}, Landroid/widget/TextView;->setTextSize(F)V\n"
        + "\n"
        + "    const/4 v2, -0x1\n"
        + "    invoke-virtual {v1, v2}, Landroid/widget/TextView;->setTextColor(I)V\n"
        + "\n"
        + "    invoke-virtual {v0, v1}, Landroid/widget/LinearLayout;->addView(Landroid/view/View;)V\n"
        + "\n"
        + "    new-instance v1, Landroid/widget/TextView;\n"
        + "    invoke-direct {v1, p0}, Landroid/widget/TextView;-><init>(Landroid/content/Context;)V\n"
        + "\n"
        + "    const-string v2, \"@@CONTENT@@\"\n"
        + "    invoke-virtual {v1, v2}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V\n"
        + "\n"
        + "    const/4 v2, -0x1\n"
        + "    invoke-virtual {v1, v2}, Landroid/widget/TextView;->setTextColor(I)V\n"
        + "\n"
        + "    const/16 v3, 0x18\n"
        + "    invoke-virtual {v1, v3, v3, v3, v3}, Landroid/widget/TextView;->setPadding(IIII)V\n"
        + "\n"
        + "    invoke-virtual {v0, v1}, Landroid/widget/LinearLayout;->addView(Landroid/view/View;)V\n"
        + "\n"
        + "    new-instance v1, Landroid/app/AlertDialog$Builder;\n"
        + "    invoke-direct {v1, p0}, Landroid/app/AlertDialog$Builder;-><init>(Landroid/content/Context;)V\n"
        + "\n"
        + "    invoke-virtual {v1, v0}, Landroid/app/AlertDialog$Builder;->setView(Landroid/view/View;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    const-string v2, \"@@BTN1@@\"\n"
        + "    const/4 v3, 0x0\n"
        + "    invoke-virtual {v1, v2, v3}, Landroid/app/AlertDialog$Builder;->setPositiveButton(Ljava/lang/CharSequence;Landroid/content/DialogInterface$OnClickListener;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    const/4 v2, @@CANCELABLE@@\n"
        + "    invoke-virtual {v1, v2}, Landroid/app/AlertDialog$Builder;->setCancelable(Z)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    invoke-virtual {v1}, Landroid/app/AlertDialog$Builder;->show()Landroid/app/AlertDialog;\n"
        + "\n"
        + "    return-void\n"
        + ".end method\n";

    private static final String S_COUNTDOWN =
        ".class public Lcom/example/injector/popup/CountdownPopup;\n"
        + ".super Ljava/lang/Object;\n"
        + "\n"
        + ".method public static show(Landroid/content/Context;)V\n"
        + "    .locals 10\n"
        + "\n"
        + "    new-instance v9, Landroid/widget/TextView;\n"
        + "    invoke-direct {v9, p0}, Landroid/widget/TextView;-><init>(Landroid/content/Context;)V\n"
        + "\n"
        + "    const-string v1, \"10\"\n"
        + "    invoke-virtual {v9, v1}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V\n"
        + "\n"
        + "    const/high16 v1, 0x41a00000\n"
        + "    invoke-virtual {v9, v1}, Landroid/widget/TextView;->setTextSize(F)V\n"
        + "\n"
        + "    new-instance v0, Landroid/widget/LinearLayout;\n"
        + "    invoke-direct {v0, p0}, Landroid/widget/LinearLayout;-><init>(Landroid/content/Context;)V\n"
        + "\n"
        + "    const/16 v1, 0x11\n"
        + "    invoke-virtual {v0, v1}, Landroid/widget/LinearLayout;->setGravity(I)V\n"
        + "\n"
        + "    const/16 v1, 0x28\n"
        + "    invoke-virtual {v0, v1, v1, v1, v1}, Landroid/widget/LinearLayout;->setPadding(IIII)V\n"
        + "\n"
        + "    invoke-virtual {v0, v9}, Landroid/widget/LinearLayout;->addView(Landroid/view/View;)V\n"
        + "\n"
        + "    new-instance v1, Landroid/app/AlertDialog$Builder;\n"
        + "    invoke-direct {v1, p0}, Landroid/app/AlertDialog$Builder;-><init>(Landroid/content/Context;)V\n"
        + "\n"
        + "    const-string v2, \"@@TITLE@@\"\n"
        + "    invoke-virtual {v1, v2}, Landroid/app/AlertDialog$Builder;->setTitle(Ljava/lang/CharSequence;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    invoke-virtual {v1, v0}, Landroid/app/AlertDialog$Builder;->setView(Landroid/view/View;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    const/4 v2, @@CANCELABLE@@\n"
        + "    invoke-virtual {v1, v2}, Landroid/app/AlertDialog$Builder;->setCancelable(Z)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    invoke-virtual {v1}, Landroid/app/AlertDialog$Builder;->show()Landroid/app/AlertDialog;\n"
        + "    move-result-object v8\n"
        + "\n"
        + "    new-instance v3, Lcom/example/injector/popup/CountdownPopup$1;\n"
        + "@@CD_LINE@@\n"
        + "    const-wide/16 v6, 0x3e8\n"
        + "    invoke-direct/range {v3 .. v9}, Lcom/example/injector/popup/CountdownPopup$1;-><init>(JJLandroid/app/AlertDialog;Landroid/widget/TextView;)V\n"
        + "\n"
        + "    invoke-virtual {v3}, Lcom/example/injector/popup/CountdownPopup$1;->start()Landroid/os/CountDownTimer;\n"
        + "\n"
        + "    return-void\n"
        + ".end method\n";

    private static final String S_COUNTDOWN_TIMER =
        ".class public Lcom/example/injector/popup/CountdownPopup$1;\n"
        + ".super Landroid/os/CountDownTimer;\n"
        + "\n"
        + ".field private final dlg:Landroid/app/AlertDialog;\n"
        + ".field private final tv:Landroid/widget/TextView;\n"
        + "\n"
        + ".method public constructor <init>(JJLandroid/app/AlertDialog;Landroid/widget/TextView;)V\n"
        + "    .registers 8\n"
        + "\n"
        + "    invoke-direct {p0, p1, p2, p3, p4}, Landroid/os/CountDownTimer;-><init>(JJ)V\n"
        + "\n"
        + "    iput-object p5, p0, Lcom/example/injector/popup/CountdownPopup$1;->dlg:Landroid/app/AlertDialog;\n"
        + "    iput-object p6, p0, Lcom/example/injector/popup/CountdownPopup$1;->tv:Landroid/widget/TextView;\n"
        + "\n"
        + "    return-void\n"
        + ".end method\n"
        + "\n"
        + ".method public onTick(J)V\n"
        + "    .locals 5\n"
        + "\n"
        + "    const-wide/16 v0, 0x3e8\n"
        + "    div-long v0, p1, v0\n"
        + "    long-to-int v0, v0\n"
        + "    add-int/lit8 v0, v0, 0x1\n"
        + "\n"
        + "    new-instance v1, Ljava/lang/StringBuilder;\n"
        + "    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V\n"
        + "\n"
        + "    const-string v2, \"\\u5269\\u4f59 \"\n"
        + "    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    const-string v2, \" \\u79d2\\u540e\\u81ea\\u52a8\\u5173\\u95ed\"\n"
        + "    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    iget-object v2, p0, Lcom/example/injector/popup/CountdownPopup$1;->tv:Landroid/widget/TextView;\n"
        + "    invoke-virtual {v2, v1}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V\n"
        + "\n"
        + "    return-void\n"
        + ".end method\n"
        + "\n"
        + ".method public onFinish()V\n"
        + "    .locals 1\n"
        + "\n"
        + "    iget-object v0, p0, Lcom/example/injector/popup/CountdownPopup$1;->dlg:Landroid/app/AlertDialog;\n"
        + "    invoke-virtual {v0}, Landroid/app/AlertDialog;->dismiss()V\n"
        + "\n"
        + "    return-void\n"
        + ".end method\n";

    private static final String S_PROGRESS =
        ".class public Lcom/example/injector/popup/ProgressPopup;\n"
        + ".super Ljava/lang/Object;\n"
        + "\n"
        + ".method public static show(Landroid/content/Context;)V\n"
        + "    .locals 12\n"
        + "\n"
        + "    new-instance v10, Landroid/widget/ProgressBar;\n"
        + "    invoke-direct {v10, p0}, Landroid/widget/ProgressBar;-><init>(Landroid/content/Context;)V\n"
        + "\n"
        + "    const/16 v1, 0x64\n"
        + "    invoke-virtual {v10, v1}, Landroid/widget/ProgressBar;->setMax(I)V\n"
        + "\n"
        + "    const/4 v1, 0x0\n"
        + "    invoke-virtual {v10, v1}, Landroid/widget/ProgressBar;->setProgress(I)V\n"
        + "\n"
        + "    new-instance v11, Landroid/widget/TextView;\n"
        + "    invoke-direct {v11, p0}, Landroid/widget/TextView;-><init>(Landroid/content/Context;)V\n"
        + "\n"
        + "    const-string v1, \"@@TEXT@@\"\n"
        + "    invoke-virtual {v11, v1}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V\n"
        + "\n"
        + "    new-instance v0, Landroid/widget/LinearLayout;\n"
        + "    invoke-direct {v0, p0}, Landroid/widget/LinearLayout;-><init>(Landroid/content/Context;)V\n"
        + "\n"
        + "    const/16 v1, 0x11\n"
        + "    invoke-virtual {v0, v1}, Landroid/widget/LinearLayout;->setGravity(I)V\n"
        + "\n"
        + "    const/16 v1, 0x28\n"
        + "    invoke-virtual {v0, v1, v1, v1, v1}, Landroid/widget/LinearLayout;->setPadding(IIII)V\n"
        + "\n"
        + "    invoke-virtual {v0, v10}, Landroid/widget/LinearLayout;->addView(Landroid/view/View;)V\n"
        + "    invoke-virtual {v0, v11}, Landroid/widget/LinearLayout;->addView(Landroid/view/View;)V\n"
        + "\n"
        + "    new-instance v1, Landroid/app/AlertDialog$Builder;\n"
        + "    invoke-direct {v1, p0}, Landroid/app/AlertDialog$Builder;-><init>(Landroid/content/Context;)V\n"
        + "\n"
        + "    const-string v2, \"@@TITLE@@\"\n"
        + "    invoke-virtual {v1, v2}, Landroid/app/AlertDialog$Builder;->setTitle(Ljava/lang/CharSequence;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    invoke-virtual {v1, v0}, Landroid/app/AlertDialog$Builder;->setView(Landroid/view/View;)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    const/4 v2, @@CANCELABLE@@\n"
        + "    invoke-virtual {v1, v2}, Landroid/app/AlertDialog$Builder;->setCancelable(Z)Landroid/app/AlertDialog$Builder;\n"
        + "    move-result-object v1\n"
        + "\n"
        + "    invoke-virtual {v1}, Landroid/app/AlertDialog$Builder;->show()Landroid/app/AlertDialog;\n"
        + "    move-result-object v9\n"
        + "\n"
        + "    new-instance v4, Lcom/example/injector/popup/ProgressPopup$1;\n"
        + "@@DUR_LINE@@\n"
        + "    const-wide/16 v7, 0x64\n"
        + "    invoke-direct/range {v4 .. v11}, Lcom/example/injector/popup/ProgressPopup$1;-><init>(JJLandroid/app/AlertDialog;Landroid/widget/ProgressBar;Landroid/widget/TextView;)V\n"
        + "\n"
        + "    invoke-virtual {v4}, Lcom/example/injector/popup/ProgressPopup$1;->start()Landroid/os/CountDownTimer;\n"
        + "\n"
        + "    return-void\n"
        + ".end method\n";

    private static final String S_PROGRESS_TIMER =
        ".class public Lcom/example/injector/popup/ProgressPopup$1;\n"
        + ".super Landroid/os/CountDownTimer;\n"
        + "\n"
        + ".field private final dlg:Landroid/app/AlertDialog;\n"
        + ".field private final pb:Landroid/widget/ProgressBar;\n"
        + ".field private final tv:Landroid/widget/TextView;\n"
        + "\n"
        + ".method public constructor <init>(JJLandroid/app/AlertDialog;Landroid/widget/ProgressBar;Landroid/widget/TextView;)V\n"
        + "    .registers 9\n"
        + "\n"
        + "    invoke-direct {p0, p1, p2, p3, p4}, Landroid/os/CountDownTimer;-><init>(JJ)V\n"
        + "\n"
        + "    iput-object p5, p0, Lcom/example/injector/popup/ProgressPopup$1;->dlg:Landroid/app/AlertDialog;\n"
        + "    iput-object p6, p0, Lcom/example/injector/popup/ProgressPopup$1;->pb:Landroid/widget/ProgressBar;\n"
        + "    iput-object p7, p0, Lcom/example/injector/popup/ProgressPopup$1;->tv:Landroid/widget/TextView;\n"
        + "\n"
        + "    return-void\n"
        + ".end method\n"
        + "\n"
        + ".method public onTick(J)V\n"
        + "    .locals 4\n"
        + "\n"
        + "@@DUR_LINE0@@\n"
        + "    sub-long v0, v0, p1\n"
        + "    const-wide/16 v2, 0x64\n"
        + "    mul-long v0, v0, v2\n"
        + "@@DUR_LINE2@@\n"
        + "    div-long v0, v0, v2\n"
        + "    long-to-int v0, v0\n"
        + "\n"
        + "    iget-object v1, p0, Lcom/example/injector/popup/ProgressPopup$1;->pb:Landroid/widget/ProgressBar;\n"
        + "    invoke-virtual {v1, v0}, Landroid/widget/ProgressBar;->setProgress(I)V\n"
        + "\n"
        + "    return-void\n"
        + ".end method\n"
        + "\n"
        + ".method public onFinish()V\n"
        + "    .locals 1\n"
        + "\n"
        + "    iget-object v0, p0, Lcom/example/injector/popup/ProgressPopup$1;->dlg:Landroid/app/AlertDialog;\n"
        + "    invoke-virtual {v0}, Landroid/app/AlertDialog;->dismiss()V\n"
        + "\n"
        + "    return-void\n"
        + ".end method\n";

    private static final String BANNER_B64 =
        "iVBORw0KGgoAAAANSUhEUgAAAUAAAACgCAIAAADywSLLAAADV0lEQVR42u3TAQ3DMAxFwUEJlEAxFEMJlEAJlCFoJ01J5UpnPQT+uk9rq7V5qF60cV+8s7wsKzf2Fw80a/QBGGCAAQYYYIABBhhggAEGGGCAAQYYYIABBhhggAEGGGCAAQYYYIABBhhggAEGGGCAAQYYYIABBhhggAEGGGCAAQYYYIABBhhggAEGGGCAAQYYYIABBhhggAEGGGCAAQYYYIABBhhggAEGGGCAAQYYYIABBhhggAEGGGCAAQYYYIABBhhggAEGGGCAAQYYYIABBhhggAEGGGCAAQYYYIABBhhggAEGGGCAAQYYYIABBhhggAEGGGCAAQYYYIABBhhggAEGGGCAAQYYYIABBhhggKsD7qv1eahetHFfvLO8LCs39hcPNGsEMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAJcHHKvFPFQv2o914p3lZVm5sb8n3j1rBDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwAADDDDAAAMMMMAAAwwwwACXB5yr5TxUL9q4L95ZXlb6xv7igWaNAAYYYIABBhhggAEGGGCAAQYYYIABBhhggAEGGGCAAQYYYIABBhhggAEGGGCAAQYYYIABBhhggAEGGGCAAQYYYIABBhhggAEGGGCAAQYYYIABBhhggAEGGGCAAQYYYIABBvjvvmAQ+y4sdCLJAAAAAElFTkSuQmCC";

    private static final Def[] ALL = {
            new Def("你好弹窗", new String[]{S_HELLO}, null),
            new Def("多按钮弹窗", new String[]{S_MULTI}, null),
            new Def("图片弹窗", new String[]{S_IMAGE}, BANNER_B64),
            new Def("输入弹窗", new String[]{S_INPUT, S_INPUT_LISTENER}, null),
            new Def("更新提示", new String[]{S_UPDATE, S_UPDATE_LISTENER}, null),
            new Def("二次元公告", new String[]{S_ANIME}, null),
            new Def("倒计时公告", new String[]{S_COUNTDOWN, S_COUNTDOWN_TIMER}, null),
            new Def("进度条弹窗", new String[]{S_PROGRESS, S_PROGRESS_TIMER}, null),
    };

    // ============================ 构建 ============================

    public static List<String> names() {
        List<String> out = new ArrayList<>();
        for (Def d : ALL) out.add(d.name);
        return out;
    }

    /**
     * 汇编并打包内置弹窗（使用默认参数）。
     *
     * @return 生成的弹窗包 zip 文件（cacheDir/builtin_popups/<name>.zip）
     */
    public static File build(Context ctx, String name, LogFn log) throws Exception {
        return build(ctx, name, log, null);
    }

    /**
     * 汇编并打包内置弹窗（应用参数覆盖）。
     * zip 内附带 popup.conf 供后续编辑。
     */
    public static File build(Context ctx, String name, LogFn log,
                             java.util.Map<String, String> cfgOverride) throws Exception {
        Def def = find(name);
        if (def == null) throw new IllegalStateException("未知的内置弹窗：" + name);
        java.util.Map<String, String> cfg = PopupConfig.withDefaults(name, cfgOverride);

        File buildRoot = new File(ctx.getCacheDir(), "builtin_build/" + safe(name));
        File smaliDir = new File(buildRoot, "smali");
        delete(smaliDir);
        smaliDir.mkdirs();
        for (String src : def.smaliFiles) {
            src = applyConfig(name, src, cfg);
            File f = new File(smaliDir, classFilePath(src));
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            writeText(f, src);
        }

        log.log("汇编内置弹窗 smali…");
        File dexFile = new File(buildRoot, "classes.dex");
        SmaliOptions opts = new SmaliOptions();
        opts.outputDexFile = dexFile.getAbsolutePath();
        opts.jobs = 2;
        opts.verboseErrors = true;
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(errBuf, true, "UTF-8"));
        System.setErr(new PrintStream(errBuf, true, "UTF-8"));
        boolean ok;
        try {
            ok = Smali.assemble(opts, smaliDir.getAbsolutePath());
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
        if (!ok || !dexFile.exists() || dexFile.length() == 0) {
            throw new IllegalStateException("内置弹窗 smali 汇编失败：" + tail(errBuf));
        }
        log.log("汇编完成 · classes.dex " + dexFile.length() + " 字节");

        String invokeLine = "invoke-static {p0}, L"
                + firstClassName(def.smaliFiles[0])
                + ";->show(Landroid/content/Context;)V";

        File outDir = new File(ctx.getCacheDir(), "builtin_popups");
        outDir.mkdirs();
        File zipFile = new File(outDir, safe(name) + ".zip");
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));
        try {
            putFile(zos, "classes.dex", dexFile);
            putText(zos, "xymods.txt", invokeLine + "\n");
            putText(zos, "popup.conf", PopupConfig.toConf(name, cfg));
            if (def.pngBase64 != null && def.pngBase64.length() > 0) {
                byte[] png = Base64.decode(def.pngBase64, Base64.DEFAULT);
                zos.putNextEntry(new ZipEntry("assets/xypopups/banner.png"));
                zos.write(png);
                zos.closeEntry();

                File cacheCopy = new File(ctx.getCacheDir(), "popup_assets/xypopups/banner.png");
                File parent = cacheCopy.getParentFile();
                if (parent != null) parent.mkdirs();
                OutputStream fo = new FileOutputStream(cacheCopy);
                fo.write(png);
                fo.close();
                log.log("assets 已就位（注入后走宿主 assets，预览时走 cache/popup_assets）");
            }
        } finally {
            zos.close();
        }
        log.log("弹窗调用行：" + invokeLine);
        log.log("弹窗包已生成：" + zipFile.getAbsolutePath());
        return zipFile;
    }

    // ============================ 参数替换 ============================

    /** 将模板占位符替换为参数值 */
    public static String applyConfig(String builtinName, String smali, java.util.Map<String, String> cfg) {
        String out = smali;
        // 文本类占位符
        for (java.util.Map.Entry<String, String> e : cfg.entrySet()) {
            String key = e.getKey().toUpperCase(java.util.Locale.ROOT);
            if (key.equals("CANCELABLE") || key.equals("SECONDS") || key.equals("MS")
                    || key.equals("UPDATE_URL")) continue;
            String token = "@@" + key + "@@";
            if (out.contains(token)) {
                out = out.replace(token, escapeSmali(e.getValue() == null ? "" : e.getValue()));
            }
        }
        // 可点击外部关闭
        boolean cancelable = !"0".equals(cfg.get("cancelable"));
        out = out.replace("@@CANCELABLE@@", cancelable ? "0x1" : "0x0");
        // 更新提示：主按钮监听器（URL 非空时绑定打开浏览器）
        if (out.contains("@@BTN1_LISTENER@@")) {
            String url = cfg.get("update_url");
            if (url == null || url.trim().isEmpty()) {
                out = out.replace("@@BTN1_LISTENER@@", "    const/4 v2, 0x0");
            } else {
                out = out.replace("@@BTN1_LISTENER@@",
                        "    const-string v1, \"" + escapeSmali(url.trim()) + "\"\n"
                        + "    new-instance v2, Lcom/example/injector/popup/UpdatePopup$1;\n"
                        + "    invoke-direct {v2, p0, v1}, Lcom/example/injector/popup/UpdatePopup$1;-><init>(Landroid/content/Context;Ljava/lang/String;)V");
            }
        }
        // 倒计时公告：总时长（秒 → 毫秒）
        if (out.contains("@@CD_LINE@@")) {
            long ms = parseLong(cfg.get("seconds"), 10L) * 1000L;
            out = out.replace("@@CD_LINE@@", wideLine("v4", ms));
        }
        // 进度条弹窗：加载时长（show 主文件 v5，onTick 里 v0/v2）
        if (out.contains("@@DUR_LINE@@") || out.contains("@@DUR_LINE0@@") || out.contains("@@DUR_LINE2@@")) {
            long ms = parseLong(cfg.get("ms"), 3000L);
            out = out.replace("@@DUR_LINE@@", wideLine("v5", ms));
            out = out.replace("@@DUR_LINE0@@", wideLine("v0", ms));
            out = out.replace("@@DUR_LINE2@@", wideLine("v2", ms));
        }
        return out;
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s == null ? "" : s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static String escapeSmali(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String wideLine(String reg, long v) {
        if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
            return "    const-wide/16 " + reg + ", 0x" + Long.toHexString(v);
        }
        if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
            return "    const-wide/32 " + reg + ", 0x" + Long.toHexString(v);
        }
        return "    const-wide " + reg + ", 0x" + Long.toHexString(v) + "L";
    }

    public static Def find(String name) {
        for (Def d : ALL) {
            if (d.name.equals(name)) return d;
        }
        return null;
    }

    private static String classFilePath(String smali) {
        Matcher m = CLASS_LINE.matcher(smali);
        if (!m.find()) throw new IllegalStateException("smali 缺少 .class 指令");
        return m.group(1) + ".smali";
    }

    private static String firstClassName(String smali) {
        Matcher m = CLASS_LINE.matcher(smali);
        if (!m.find()) throw new IllegalStateException("smali 缺少 .class 指令");
        return m.group(1);
    }

    private static String safe(String name) {
        return name.replaceAll("[^0-9A-Za-z\\u4e00-\\u9fa5]", "_");
    }

    private static void writeText(File f, String text) throws Exception {
        OutputStream os = new FileOutputStream(f);
        os.write(text.getBytes("UTF-8"));
        os.close();
    }

    private static void putFile(ZipOutputStream zos, String entryName, File f) throws Exception {
        zos.putNextEntry(new ZipEntry(entryName));
        InputStream in = new FileInputStream(f);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
        in.close();
        zos.closeEntry();
    }

    private static void putText(ZipOutputStream zos, String entryName, String text) throws Exception {
        zos.putNextEntry(new ZipEntry(entryName));
        zos.write(text.getBytes("UTF-8"));
        zos.closeEntry();
    }

    private static void delete(File f) {
        if (f == null || !f.exists()) return;
        File[] files = f.listFiles();
        if (files != null) {
            for (File c : files) delete(c);
        }
        f.delete();
    }

    private static String tail(ByteArrayOutputStream buf) {
        String s;
        try {
            s = buf.toString("UTF-8").trim();
        } catch (Exception e) {
            s = "";
        }
        if (s.isEmpty()) return "无错误输出";
        return s.length() > 1500 ? s.substring(s.length() - 1500) : s;
    }
}
