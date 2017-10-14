# Recorder

参考微信实现的短视频录像

![预览](https://github.com/pye52/Recorder/blob/master/recorder.gif)

```groovy
allprojects {
	repositories {
		...
		maven { url 'https://jitpack.io' }
	}
}

dependencies {
	compile 'com.github.pye52:Recorder:2.0.1'
}
```



在manifest中添加

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
<uses-permission android:name="android.permission.CAMERA"/>

<activity android:name="com.kanade.recorder.Recorder"
            android:screenOrientation="portrait"
            android:theme="@style/Theme.AppCompat.Light.NoActionBar"/>
```


### 如何使用

启动录像：

```java
// 录像保存地址
String filepath = ....
Intent intent = Recorder.newIntent(context, filepath);
startActivityForResult(intent, requestCode)
```

获取返回结果：

```java
@Override
protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
  	if (data == null){
    	return;
  	}
	RecorderResult result = Recorder.getResult(data);
    // 录像文件保存地址
    String filepath = result.getFilepath();
    // 录像时长
    int duration = result.getDuration();
}
```

