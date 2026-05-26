package OpenSSL::safe::installdata;

use strict;
use warnings;
use Exporter;
our @ISA = qw(Exporter);
our @EXPORT = qw(
    @PREFIX
    @libdir
    @BINDIR @BINDIR_REL_PREFIX
    @LIBDIR @LIBDIR_REL_PREFIX
    @INCLUDEDIR @INCLUDEDIR_REL_PREFIX
    @APPLINKDIR @APPLINKDIR_REL_PREFIX
    @ENGINESDIR @ENGINESDIR_REL_LIBDIR
    @MODULESDIR @MODULESDIR_REL_LIBDIR
    @PKGCONFIGDIR @PKGCONFIGDIR_REL_LIBDIR
    @CMAKECONFIGDIR @CMAKECONFIGDIR_REL_LIBDIR
    $COMMENT $VERSION @LDLIBS
);

our $COMMENT                    = '';
our @PREFIX                     = ( '/mnt/data/andsi/android-floating-lyrics-main/lyrics-core/target/x86_64-linux-android/release/build/openssl-sys-e6a739a4814bbdfe/out/openssl-build/install' );
our @libdir                     = ( '/mnt/data/andsi/android-floating-lyrics-main/lyrics-core/target/x86_64-linux-android/release/build/openssl-sys-e6a739a4814bbdfe/out/openssl-build/install/lib' );
our @BINDIR                     = ( '/mnt/data/andsi/android-floating-lyrics-main/lyrics-core/target/x86_64-linux-android/release/build/openssl-sys-e6a739a4814bbdfe/out/openssl-build/install/bin' );
our @BINDIR_REL_PREFIX          = ( 'bin' );
our @LIBDIR                     = ( '/mnt/data/andsi/android-floating-lyrics-main/lyrics-core/target/x86_64-linux-android/release/build/openssl-sys-e6a739a4814bbdfe/out/openssl-build/install/lib' );
our @LIBDIR_REL_PREFIX          = ( 'lib' );
our @INCLUDEDIR                 = ( '/mnt/data/andsi/android-floating-lyrics-main/lyrics-core/target/x86_64-linux-android/release/build/openssl-sys-e6a739a4814bbdfe/out/openssl-build/install/include' );
our @INCLUDEDIR_REL_PREFIX      = ( 'include' );
our @APPLINKDIR                 = ( '/mnt/data/andsi/android-floating-lyrics-main/lyrics-core/target/x86_64-linux-android/release/build/openssl-sys-e6a739a4814bbdfe/out/openssl-build/install/include/openssl' );
our @APPLINKDIR_REL_PREFIX      = ( 'include/openssl' );
our @ENGINESDIR                 = ( '/mnt/data/andsi/android-floating-lyrics-main/lyrics-core/target/x86_64-linux-android/release/build/openssl-sys-e6a739a4814bbdfe/out/openssl-build/install/lib/engines-3' );
our @ENGINESDIR_REL_LIBDIR      = ( 'engines-3' );
our @MODULESDIR                 = ( '/mnt/data/andsi/android-floating-lyrics-main/lyrics-core/target/x86_64-linux-android/release/build/openssl-sys-e6a739a4814bbdfe/out/openssl-build/install/lib/ossl-modules' );
our @MODULESDIR_REL_LIBDIR      = ( 'ossl-modules' );
our @PKGCONFIGDIR               = ( '/mnt/data/andsi/android-floating-lyrics-main/lyrics-core/target/x86_64-linux-android/release/build/openssl-sys-e6a739a4814bbdfe/out/openssl-build/install/lib/pkgconfig' );
our @PKGCONFIGDIR_REL_LIBDIR    = ( 'pkgconfig' );
our @CMAKECONFIGDIR             = ( '/mnt/data/andsi/android-floating-lyrics-main/lyrics-core/target/x86_64-linux-android/release/build/openssl-sys-e6a739a4814bbdfe/out/openssl-build/install/lib/cmake/OpenSSL' );
our @CMAKECONFIGDIR_REL_LIBDIR  = ( 'cmake/OpenSSL' );
our $VERSION                    = '3.6.2';
our @LDLIBS                     =
    # Unix and Windows use space separation, VMS uses comma separation
    $^O eq 'VMS'
    ? split(/ *, */, '-ldl -pthread ')
    : split(/ +/, '-ldl -pthread ');

1;
