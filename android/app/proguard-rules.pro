# 本文件由 app/build.gradle.kts 的 release 构建引用。
# 当前 isMinifyEnabled = false，所以规则暂时不生效；留空文件是为了避免
# 首次开启混淆时因文件缺失而构建失败。
#
# 将来开启混淆时注意：
# - ZXing 不做反射，无需 keep 规则
# - Gson 反序列化的数据类（model/ 下）需要 keep，否则字段名会被混淆掉
