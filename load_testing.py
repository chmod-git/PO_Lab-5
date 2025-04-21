from locust import HttpUser, task

class StaticWebUser(HttpUser):
    @task
    def load_index(self):
        self.client.get("/index.html")

    @task
    def load_page2(self):
        self.client.get("/page2.html")
