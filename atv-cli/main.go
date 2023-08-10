package main

import (
    "net/http"
    "strings"
    "io"
	"io/ioutil"
	"github.com/skip2/go-qrcode"
    "github.com/gin-gonic/gin"
	"os"
	"os/exec"
)

func main() {
    r := gin.Default()
	r.GET("/qrcode", generateQR)
	r.POST("/upgrade", upgrade)
	r.Run(":3456")
}

func generateQR(c *gin.Context) {
    url := c.Query("url")
    png, err := qrcode.Encode(url, qrcode.Medium, 256)
    if err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
    } else {
        c.Data(200, "image/png", png)
    }
}

func upgrade(c *gin.Context) {
    command, err := readCommandLine()
    s := strings.Fields(command)
    var script = ""
    if s[0] == "host" {
        script = "/tmp/update_hostmode.sh"
        if err = downloadFile("https://d.har01d.cn/update_hostmode.sh", script); err != nil {

        }
    } else {
        script = "/tmp/update_xiaoya.sh"
        if err = downloadFile("https://d.har01d.cn/update_xiaoya.sh", script); err != nil {

        }
    }
    cmd, err := exec.Command("/bin/sh", script, strings.Join(s[1:], " ")).Output()
    if err != nil {

    }
    output := string(cmd)
    c.JSON(http.StatusOK, gin.H{"result": output})
}

func readCommandLine() (string, error) {
   fileContent, err := ioutil.ReadFile("/root/.config/atv/cmd")
   if err != nil {
      return "", err
   }

   return string(fileContent), nil
}

func downloadFile(url string, filepath string) error {
	out, err := os.Create(filepath)
	if err != nil {
		return err
	}
	defer out.Close()

	resp, err := http.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	_, err = io.Copy(out, resp.Body)
	if err != nil {
		return err
	}

	return nil
}
