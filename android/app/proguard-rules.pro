# PromptCam ProGuard rules

# Keep protocol constants
-keep class com.wembassy.promptcam.Protocol { *; }

# Keep settings enums
-keep class com.wembassy.promptcam.CameraLens { *; }
-keep class com.wembassy.promptcam.VideoResolution { *; }
-keep class com.wembassy.promptcam.AppSettings { *; }

# Keep data classes used in serialization
-keep class com.wembassy.promptcam.streaming.ControlServer$TeleprompterText { *; }
-keep class com.wembassy.promptcam.streaming.TcpServer$ClientConnection { *; }

# Kotlin metadata
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
