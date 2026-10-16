# Peta (Leaflet) memanggil aplikasi lewat objek "Bridge" di WebView. Nama metodenya dipanggil dari JavaScript,
# jadi tidak boleh diganti nama atau dibuang oleh R8.
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
