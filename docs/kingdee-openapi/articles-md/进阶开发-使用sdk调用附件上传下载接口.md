# 使用sdk调用附件上传下载接口

> 来源: https://vip.kingdee.com/knowledge/specialDetail/229961573895771136?category=229964434343990272&id=392991096756120320&type=Knowledge&productLineId=1&lang=zh-CN

> 抓取时间: 2026-09-03T04:31:45.724Z

---

产品版本说明

星空产品版本为：补丁版本为PT146897【7.7.2374.8】及以上

使用说明

1、上传附件接口(上传附件并绑定单据)

参数说明：

![](https://vip.kingdee.com/download/010938ab8362db38459a9f7931180ffa4fdc.png)

注意：如果是单据头附件，EntryinterId要么不填，要么填-1。

请求示例：

```
var clienter = new K3CloudApi();
string materid="";//物料单据Id(单据内码)
string number="";//物料单据编码
string jsonData = 
"{\"FileName\":\"1016.txt\",\"FEntryKey\":\"\",\"FormId\": \"BD_MATERIAL\",\"IsLast\": true," + 
"\"InterId\": " + materid + "," + "\"BillNO\": " + "\"" + Number + "\"" + 
",\"AliasFileName\": \"test\",\"SendByte\": \"6L+Z5piv5rWL6K+V5paH5Lu25ZKMYmFzZTY05a2X56ym5Liy5LqS6L2s\"}";
var resultJson = clienter.AttachmentUpLoad(jsonData);
```

附件过大，上传失败，需要使用分块上传

请求示例：

```
public void uploadFiles() throws Exception {
        K3CloudApi api = new K3CloudApi();
        String formId = "PUR_PurchaseOrder";
        String billNo = "CGDD001939";
        String id = "107361";
        String fileName = "image.png";
        String filePath = "D:\\" + fileName;
        int blockSize = 1024 * 1024; // 分块大小：1M
        File file = new File(filePath);
        if (file.length() <= 0) {
            throw new Exception("文件内容不允许为空。");
        }
        FileInputStream fis = new FileInputStream(file);
        byte[] content = new byte[blockSize];
        String fileId = "";
        while (true) {
            int size = fis.read(content);
            if (size == 0) {
                break;
            }
            boolean isLast = (size != blockSize);
            byte[] uploadBytes = Arrays.copyOf(content,  size);
            String fileBase64String = new BASE64Encoder().encode(uploadBytes);
            Map<String, Object> request = new HashMap<>();
            request.put("FileName", fileName);
            request.put("FormId", formId);
            request.put("IsLast", isLast);
            request.put("InterId", id);
            request.put("BillNo", billNo);
            request.put("AliasFileName", "test");
            request.put("FileId ", fileId);
            request.put("SendByte", fileBase64String);
            String result = api.attachmentUpload(new Gson().toJson(request));
            System.out.println(result);
            JsonObject resultObject = new JsonParser().parse(result).getAsJsonObject();
            JsonObject data = resultObject.get("Result").getAsJsonObject();
            // 第一个分块上传时，FileId是空，从第二个分块开始，FileId不能再为空
            fileId = data.get("FileId").getAsString();
            if (isLast) {
                break;
            }
        }
    }
```

2、下载附件接口

参数说明：

![](https://vip.kingdee.com/download/01095ed08a56efb6424080ef87e497b9fdbb.png)

请求示例：

```
var clienter = new K3CloudApi();
string fileid = "";//文件Id
string jsonData = "{\"FileId\": " +"\""+ fileid + "\""+", \"StartIndex\": 0}";
var resultJson = clienter.AttachmentDownLoad(jsonData);
```

附件过大，下载文件不完整，需要使用分块下载

请求示例：

```
 public void saveFiles() throws Exception {
        String fileid = "c7be8e41c0d64ee1b1f56f32f1ec6e8c";
        K3CloudApi api = new K3CloudApi();
        String json = " {\"FileId\": " + "\"" + fileid + "\"" + ", \"StartIndex\": 0}";
        String result = api.attachmentDownLoad(json);
        JsonObject resultObject = new JsonParser().parse(result).getAsJsonObject();
        JsonObject data = resultObject.get("Result").getAsJsonObject();
        String fileName = data.get("FileName").getAsString();
        boolean isLast = data.get("IsLast").getAsBoolean();
        if (isLast) {
            System.out.println("小文件");
            String filePart = data.get("FilePart").getAsString();
            File file = new File("D:\\" + fileName);
            try {

                byte[] b = Base64.getDecoder().decode(filePart);
                for (int i = 0; i < b.length; ++i) {
                    if (b[i] < 0) {//调整异常数据
                        b[i] += 256;
                    }
                }
                //生成文件
                OutputStream out = new FileOutputStream(file.getPath());
                out.write(b);
                out.flush();
                out.close();
            } catch (Exception e) {
                System.out.println("文件保存异常:" + e.getMessage());
            }
        } else {
            System.out.println("大文件");
            List<String> list = new ArrayList<>();
            String filePart = data.get("FilePart").getAsString();
            list.add(filePart);
            while (!data.get("IsLast").getAsBoolean()) {
                int startIndex = data.get("StartIndex").getAsInt();
                String request = "{\"FileId\": " + "\"" + fileid + "\"" + ", \"StartIndex\": " + startIndex + "}";
                result = api.attachmentDownLoad(request);
                resultObject = new JsonParser().parse(result).getAsJsonObject();
                data = resultObject.get("Result").getAsJsonObject();
                filePart = data.get("FilePart").getAsString();
                list.add(filePart);
            }
            File file = new File("D:\\" + fileName);
            try {
                for (int j = 0; j < list.size(); j++) {

                    byte[] b = Base64.getDecoder().decode(list.get(j));
                    for (int i = 0; i < b.length; ++i) {
                        if (b[i] < 0) {//调整异常数据
                            b[i] += 256;
                        }
                    }
                    //生成文件
                    OutputStream out = new FileOutputStream(file.getPath(), true);
                    out.write(b);
                    out.flush();
                    out.close();
                }
            } catch (Exception e) {
                System.out.println("文件保存异常:" + e.getMessage());
            }

        }
    }
```
