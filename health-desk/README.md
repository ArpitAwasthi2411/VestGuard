# Health-centre desk (demo)

A browser dashboard for a Primary Health Centre: households with a vest, live alerts, case timelines and help-on-the-way routes on a map.

```bash
python3 phc_server.py            # Python 3 standard library only
# then open the address it prints, e.g. http://localhost:8090/phc.html
```

- On the same Wi-Fi as the household phones, alerts arrive over UDP.
- Over the internet, enable "Also receive alerts over the internet" in the dashboard settings and list the families' codes (push relay: ntfy).

This is a demonstration of the scale-up model, not a deployed service.
