DESS Monitor replies.

- `recorded_devcode_*_query_device_last_data.json` are real replies recorded from DESS accounts,
  copied unchanged from github.com/andreas-glaser/ha-dessmonitor (tests/fixtures), MIT License,
  Copyright (c) 2025 Andreas Christoph Glaser. 6416 is a PowMr POW-HVM6.2M-48V-N running SBU on
  battery with the mains present; 2477 is a 48 V / 5 kW unit in Line Mode.
- Everything else is synthetic: the querySPDeviceLastData shape (gts plus pars grouped as gd_,
  sy_, pv_, bt_, bc_) and the err / dat envelope follow the integrations above, with made-up values.
