# SPDX-License-Identifier: GPL-3.0-or-later
#
# ✅ VERIFIED (2026-07-31): builds, boots, serves the gpoddersync API, and
# :core:gpodder's OpodsyncIntegrationTest passes against it. See docs/journal.md.
#
# opodsync (https://github.com/kd2org/opodsync, AGPL-3.0) ships no Dockerfile of its
# own — it is plain PHP + SQLite intended to be dropped into a webroot — so this
# builds a minimal one. We only *run* this server for tests; we never link against
# it or ship it, so its AGPL licence does not reach Podsilo (CLAUDE.md §2/§4).
FROM php:8.3-apache

# Pinned to a tag rather than a moving branch, per CLAUDE.md §3's "no floating
# versions" spirit. Bump deliberately. 0.5.3 is the newest tag as of 2026-07-31 —
# note opodsync has never released a 1.x; the upstream version line is 0.x.
ARG OPODSYNC_REF=0.5.3

# No PHP extensions to install: php:8.3-apache already has sqlite3, pdo_sqlite and
# json compiled in (verified with `php -m`), which is opodsync's full requirement
# set (README: "PHP 7.4+ and SQLite3 with JSON1"). It uses the procedural \SQLite3
# class, not PDO. curl is here for the compose healthcheck; git for the clone.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl git \
    && rm -rf /var/lib/apt/lists/* \
    # opodsync's .htaccess is load-bearing, not cosmetic: `FallbackResource /index.php`
    # is what routes the virtual API paths (/index.php/apps/gpoddersync/...) into the
    # front controller, and the `SetEnvIf Authorization` line is what makes the Basic
    # auth header visible to PHP at all. Debian's apache2.conf ships AllowOverride None
    # for /var/www/, which silently ignores the file — every API call then 404s and auth
    # never arrives. Overriding it is mandatory.
    && printf '%s\n' \
        '<Directory /var/www/html/>' \
        '    AllowOverride All' \
        '</Directory>' \
        > /etc/apache2/conf-available/opodsync.conf \
    && a2enconf opodsync \
    # Port 80 needs root, and this image drops root below. The official image already
    # gives www-data Apache's run, lock and log directories.
    && sed -i 's/^Listen 80$/Listen 8080/' /etc/apache2/ports.conf \
    && sed -i 's/<VirtualHost \*:80>/<VirtualHost *:8080>/' /etc/apache2/sites-available/000-default.conf \
    && git clone --depth 1 --branch "${OPODSYNC_REF}" \
        https://github.com/kd2org/opodsync.git /tmp/opodsync \
    && cp -r /tmp/opodsync/server/. /var/www/html/ \
    && cp /tmp/opodsync/config.dist.php /var/www/config.dist.php \
    && rm -rf /tmp/opodsync \
    # DATA_ROOT defaults to <webroot>/data (server/_inc.php), and that is also where
    # opodsync looks for config.local.php — so the volume mounts there, not at
    # /var/www/data. The .htaccess above 404s /data/* so it stays unreachable.
    && mkdir -p /var/www/html/data \
    && chown -R www-data:www-data /var/www/html

COPY opodsync-entrypoint.sh /usr/local/bin/opodsync-entrypoint.sh
RUN chmod +x /usr/local/bin/opodsync-entrypoint.sh

USER www-data

ENTRYPOINT ["/usr/local/bin/opodsync-entrypoint.sh"]
CMD ["apache2-foreground"]
