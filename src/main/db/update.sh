#!/bin/bash -e

cd /data2/rush

FULLEXPORT="http://ftp.musicbrainz.org/pub/musicbrainz/data/fullexport"
WORKDIR='/data2/rush'
MBSLAVE="$WORKDIR/mbslave"
MBSMICRO_SERVICE='/etc/sv/mbz-micro'
CHECK_URL="http://127.0.0.1:9090/release/artistName/David%20Bowie"

CURRENT_DB=`cat $MBSMICRO_SERVICE/env/MBZ_DB_URL | cut -d '/' -f 4`
echo "Current DB is $CURRENT_DB"

LATEST=`curl -s $FULLEXPORT/LATEST`
LATEST_DB="musicbrainz_`echo $LATEST | sed s/-/_/g`"

echo Latest dump is "$LATEST"

if [ "$CURRENT_DB" = "$LATEST_DB" ]; then
    echo "Database is up to date"
    DF=`df -h | grep /data2 | awk '{ print $2 "%20" $3 "%20" $4 "%20" $5}'`
    curl -XGET -vvvvv "http://allmusicrating.com/ajaxcron/?secret=uc@sAircVroa*EtniRns%gYeSrEv\$\$CiRcEeTa_KlE2lYm&email=elias_n@netvision.net.il,dimax4@gmail.com&event_type=up_to_date&df=$DF"

    exit 0
fi

echo Starting fullexport.
echo getting mbdump.tar.bz2
curl -sS ${FULLEXPORT}/${LATEST}/mbdump.tar.bz2 -o $WORKDIR/mbdump/mbdump.tar.bz2

echo getting mbdump-derived.tar.bz2
curl -sS ${FULLEXPORT}/${LATEST}/mbdump-derived.tar.bz2 -o $WORKDIR/mbdump/mbdump-derived.tar.bz2

echo creating database
sudo -u postgres dropdb $LATEST_DB || true
sudo -u postgres createdb -p 5432 -l C -E UTF-8 -T template0 -O musicbrainz $LATEST_DB

sed -i "s/^name=.*$/name=$LATEST_DB/" $MBSLAVE/mbslave.conf

echo creating schema musicbrainz
echo 'CREATE SCHEMA musicbrainz;' | $MBSLAVE/mbslave-psql.py -S
echo creating tables

$MBSLAVE/mbslave-remap-schema.py <$MBSLAVE/sql/CreateTables.sql | sed 's/\(CUBE\|JSONB\)/TEXT/' | $MBSLAVE/mbslave-psql.py

echo importing data
$MBSLAVE/mbslave-import.py $WORKDIR/mbdump/mbdump.tar.bz2 $WORKDIR/mbdump/mbdump-derived.tar.bz2

echo creating primary keys
$MBSLAVE/mbslave-remap-schema.py <$MBSLAVE/sql/CreatePrimaryKeys.sql | $MBSLAVE/mbslave-psql.py
echo creating indexes
$MBSLAVE/mbslave-remap-schema.py <$MBSLAVE/sql/CreateIndexes.sql | \
	grep -vE '(collate|medium_index|ll_to_earth)' | \
        sed 's/USING BRIN//g' | \
        perl -pe 'BEGIN { undef $/; } s{^CREATE INDEX edit_data_idx_link_type .*?;}{}smg' | \
        $MBSLAVE/mbslave-psql.py

echo creating more indexes

echo "CREATE INDEX medium_release  ON medium  USING BTREE  (release)" | $MBSLAVE/mbslave-psql.py
echo "CREATE INDEX track_idx_medium  ON track  USING BTREE  (medium, \"position\")" | $MBSLAVE/mbslave-psql.py

echo creating views
$MBSLAVE/mbslave-remap-schema.py <$MBSLAVE/sql/CreateViews.sql | $MBSLAVE/mbslave-psql.py

echo "Adding columns for fts"
$MBSLAVE/mbslave-remap-schema.py <$WORKDIR/mbz-micro/src/main/db/tsvector.sql | $MBSLAVE/mbslave-psql.py

echo executing vacuum analyze
echo 'VACUUM ANALYZE;' | $MBSLAVE/mbslave-psql.py

echo "New database created"

sudo sed -i "s/$CURRENT_DB/$LATEST_DB/" $MBSMICRO_SERVICE/env/MBZ_DB_URL 
sudo sv restart $MBSMICRO_SERVICE

sleep 30

echo "Obtaining http status"
http_status=`curl -s -o /dev/null -w "%{http_code}" $CHECK_URL`
echo "Obtaining response size"
response_size=`curl -s -o /dev/null -w "%{size_download}" $CHECK_URL`
#set +e
echo "Checking response"
curl -sS $CHECK_URL | python -c 'import sys, json; assert json.load(sys.stdin)'
STATUS=$?
#set -e

if [[ $STATUS = 0 && $http_status = '200' && $response_size -gt 5000 ]]
then
    echo "Success"
    # Deleting old databases
    SQL="select pg_database.datname from pg_database where datname LIKE 'musicbrainz%' AND datname != '$CURRENT_DB' and datname != '$LATEST_DB'"
    for db in `sudo -u postgres psql -t -c "$SQL" --no-align`; do
	echo "Deleting old db $db"
	sudo -u postgres dropdb $db
    done

    DF=`df -h | grep /data2 | awk '{ print $2 "%20" $3 "%20" $4 "%20" $5}'`
    curl -XGET "http://allmusicrating.com/ajaxcron/?secret=uc@sAircVroa*EtniRns%gYeSrEv\$\$CiRcEeTa_KlE2lYm&email=elias_n@netvision.net.il,dimax4@gmail.com&event_type=replication_succeeded&df=$DF"

else
    sudo sed -i "s/$LATEST_DB/$CURRENT_DB/" $MBSMICRO_SERVICE/env/MBZ_DB_URL
    sudo sv restart $MBSMICRO_SERVICE
    echo "New database loaded with errors. Rolled back to old one"
    DF=`df -h | grep /data2 | awk '{ print $2 "%20" $3 "%20" $4 "%20" $5}'`
    curl -XGET -vvvvv "http://allmusicrating.com/ajaxcron/?secret=uc@sAircVroa*EtniRns%gYeSrEv\$\$CiRcEeTa_KlE2lYm&email=elias_n@netvision.net.il,dimax4@gmail.com&event_type=replication_failed&df=$DF"

    exit 1
fi













