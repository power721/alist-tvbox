"""Trusted offline fixture; no site or media requests."""
PLUGIN_ID = "maintenance-demo"


class Spider:
    def init(self, extend=""):
        return ""

    def getName(self):
        return "Maintenance demo"

    def homeContent(self, filter):
        return {"class": [{"type_id": "1", "type_name": "Demo"}]}

    def homeVideoContent(self):
        return {"list": []}

    def categoryContent(self, tid, pg, filter, extend):
        return {"page": int(pg), "list": [{"vod_id": "demo-1", "vod_name": "Offline fixture"}]}

    def detailContent(self, ids):
        if ids != ["demo-1"]:
            return {"list": []}
        return {"list": [{"vod_id": ids[0], "vod_name": "Offline fixture",
                          "vod_pic": "", "vod_content": "Trusted offline fixture",
                          "vod_play_from": "demo", "vod_play_url": "Episode 1$demo-play"}]}

    def searchContent(self, key, quick, pg="1"):
        return {"list": [{"vod_id": "demo-1", "vod_name": key + ":" + str(pg)}]}

    def playerContent(self, flag, id, vipFlags):
        if flag != "demo" or id != "demo-play":
            raise ValueError("Unexpected playback input")
        return {"parse": 0, "jx": 0, "playUrl": "",
                "url": "https://example.invalid/fixture.mp4", "header": {}}

    def localProxy(self, params):
        return [404, "text/plain", "No proxy in offline fixture"]

    def destroy(self):
        return None
