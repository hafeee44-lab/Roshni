Synthetic Growatt replies. None of these came from a real account: they are built from the field
names and units in Home Assistant's growatt_server integration (sensor/sph.py, sensor/tlx.py,
sensor/mix.py, sensor/storage.py and the V1 fixtures in tests/components/growatt_server/conftest.py)
and growattServer's docstrings (base_api.mix_system_status, open_api_v1.device_list). Replace them
with recorded replies once someone with a Growatt account captures some.
