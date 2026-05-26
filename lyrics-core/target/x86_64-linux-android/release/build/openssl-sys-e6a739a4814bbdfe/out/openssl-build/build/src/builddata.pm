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

our $COMMENT                    = 'This file should be used when building against this OpenSSL build, and should never be installed';
our @PREFIX                     = ( '/mnt/data/andsi/android-floating-lyrics-main/lyrics-core/target/x86_64-linux-android/release/build/openssl-sys-e6a739a4814bbdfe/out/openssl-build/build/src' );
our @libdir                     = ( '' );
our @BINDIR                     = ( '/mnt/data/andsi/android-floating-lyrics-main/lyrics-core/target/x86_64-linux-android/release/build/openssl-sys-e6a739a4814bbdfe/out/openssl-build/build/src/apps' );
our @BINDIR_REL_PREFIX          = ( 'apps' );
our @LIBDIR                     = ( '/mnt/data/andsi/android-floating-lyrics-main/lyrics-core/target/x86_64-linux-android/release/build/openssl-sys-e6a739a4814bbdfe/out/openssl-build/build/src' );
our @LIBDIR_REL_PREFIX          = ( '' );
our @INCLUDEDIR                 = ( '/mnt/data/andsi/android-floating-lyrics-main/lyrics-core/target/x86_64-linux-android/release/build/openssl-sys-e6a739a4814bbdfe/out/openssl-build/build/src/include', '/mnt/data/andsi/android-floating-lyrics-main/lyrics-core/target/x86_64-linux-android/release/build/openssl-sys-e6a739a4814bbdfe/out/openssl-build/build/src/include' );
our @INCLUDEDIR_REL_PREFIX      = ( 'include', './include' );
our @APPLINKDIR                 = ( '/mnt/data/andsi/android-floating-lyrics-main/lyrics-core/target/x86_64-linux-android/release/build/openssl-sys-e6a739a4814bbdfe/out/openssl-build/build/src/ms' );
our @APPLINKDIR_REL_PREFIX      = ( 'ms' );
our @ENGINESDIR                 = ( '/mnt/data/andsi/android-floating-lyrics-main/lyrics-core/target/x86_64-linux-android/release/build/openssl-sys-e6a739a4814bbdfe/out/openssl-build/build/src/engines' );
our @ENGINESDIR_REL_LIBDIR      = ( 'engines' );
our @MODULESDIR                 = ( '/mnt/data/andsi/android-floating-lyrics-main/lyrics-core/target/x86_64-linux-android/release/build/openssl-sys-e6a739a4814bbdfe/out/openssl-build/build/src/providers' );
our @MODULESDIR_REL_LIBDIR      = ( 'providers' );
our @PKGCONFIGDIR               = ( '/mnt/data/andsi/android-floating-lyrics-main/lyrics-core/target/x86_64-linux-android/release/build/openssl-sys-e6a739a4814bbdfe/out/openssl-build/build/src' );
our @PKGCONFIGDIR_REL_LIBDIR    = ( '.' );
our @CMAKECONFIGDIR             = ( '/mnt/data/andsi/android-floating-lyrics-main/lyrics-core/target/x86_64-linux-android/release/build/openssl-sys-e6a739a4814bbdfe/out/openssl-build/build/src' );
our @CMAKECONFIGDIR_REL_LIBDIR  = ( '.' );
our $VERSION                    = '3.6.2';
our @LDLIBS                     =
    # Unix and Windows use space separation, VMS uses comma separation
    $^O eq 'VMS'
    ? split(/ *, */, '-ldl -pthread ')
    : split(/ +/, '-ldl -pthread ');

1;
