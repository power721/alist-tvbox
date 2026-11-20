"""Trusted home-merge fixture; all cards are inert and no network is used."""
import json


class Spider:
    backend_parse = False

    def init(self, extend=""):
        options = json.loads(extend or "{}")
        self.scenario = options.get("homeScenario", "replace")
        self.backend_parse = bool(options.get("backendParse", False))
        self.events = []

    def getName(self):
        return "Maintenance home contract"

    def homeContent(self, filter):
        self.events.append("home:" + str(filter))
        if self.scenario == "home_error":
            raise ValueError("LAB_HOME_CALLBACK_FAILED")
        if self.scenario == "null_home":
            return None
        if self.scenario == "invalid_home":
            return []
        body = {"class": [{"type_id": "lab", "type_name": "Home"}],
                "filters": {"lab": [{"key": "sort", "name": "Sort", "value": []}]},
                "home_metadata": {"keep": True}, "list": [self.card("base", "Base")]}
        if self.scenario == "separate":
            body.pop("list")
        if self.scenario == "invalid_home_list":
            body["list"] = "invalid"
        return json.dumps(body) if self.scenario == "json" else body

    def homeVideoContent(self):
        self.events.append("video")
        if self.scenario == "video_error":
            raise ValueError("LAB_VIDEO_CALLBACK_FAILED")
        if self.scenario == "null_video":
            return None
        if self.scenario == "no_list":
            return {"ignored_video_metadata": True}
        if self.scenario == "null_video_list":
            return {"list": None}
        if self.scenario == "invalid_video":
            return []
        if self.scenario == "invalid_video_list":
            return {"list": "invalid"}
        if self.scenario == "invalid_video_json":
            return "{broken"
        body = {"list": [self.card("rec", "First"), self.card("rec", "Second")],
                "ignored_video_metadata": True}
        if self.scenario == "empty_video":
            body["list"] = []
        return json.dumps(body) if self.scenario == "json" else body

    def categoryContent(self, tid, pg, filter, extend):
        return {"list": [], "lab_events": list(self.events)}

    def card(self, id, name):
        return {"vod_id": id, "vod_name": name, "vod_pic": "https://example.invalid/" + id + ".jpg"}
