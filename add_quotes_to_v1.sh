#!/bin/bash
# Add quotes to column names in V1_new_install.sql to force lowercase in H2

sed -i \
  -e 's/\brelease_year\b/"release_year"/g' \
  -e 's/\bitem_key\b/"item_key"/g' \
  -e 's/\bnav_value\b/"nav_value"/g' \
  -e 's/\bextension\b/"extension"/g' \
  -e 's/\bplugin_version\b/"plugin_version"/g' \
  -e 's/\bsetting_value\b/"setting_value"/g' \
  src/main/resources/db/migration/current/V1_new_install.sql

echo "Done! Column names now have quotes to force lowercase storage in H2."
echo "Please rebuild and test."
