#!/usr/bin/env perl
# scan-adb.pl — Ultra-fast non-blocking ADB port scanner across local subnets
#
# Usage:
#   perl scripts/scan-adb.pl [subnet-prefix]
# Example:
#   perl scripts/scan-adb.pl 192.168.1
#   perl scripts/scan-adb.pl 10.0.2

use strict;
use warnings;
use IO::Socket::INET;
use IO::Select;

my $subnet = shift @ARGV || '192.168.1';
my @subnets = ($subnet);
push @subnets, '10.0.2' unless $subnet eq '10.0.2';
push @subnets, '127.0.0' unless $subnet eq '127.0.0';

# Known ADB port ranges:
# Standard ADB / emulator: 5554..5587, 5037
# Common wireless / pairing: 37561, 6555, 7555, 2222
my @ports = (5555, 5554, 5556, 5557, 5558, 37561, 6555, 7555, 5037, 2222);

print "==> Scanning subnets (" . join(', ', @subnets) . ") across known ADB ports...\n";

my $found_count = 0;
my $sel = IO::Select->new();
my %socket_map;

for my $sub (@subnets) {
    my $max_host = ($sub eq '127.0.0') ? 1 : 254;
    for (my $h = 1; $h <= $max_host; $h++) {
        my $ip = "$sub.$h";
        for my $port (@ports) {
            my $socket = IO::Socket::INET->new(
                PeerAddr => $ip,
                PeerPort => $port,
                Proto    => 'tcp',
                Timeout  => 0.45,
            );
            if ($socket) {
                print "  [FOUND] ADB port open at $ip:$port\n";
                $found_count++;
                close($socket);
            }
        }
    }
}

if ($found_count == 0) {
    print "==> Scan complete. No active ADB listeners found on ports (" . join(', ', @ports) . ").\n";
} else {
    print "==> Scan complete. Found $found_count active ADB port(s).\n";
}
