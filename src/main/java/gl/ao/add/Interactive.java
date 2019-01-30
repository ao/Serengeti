package gl.ao.add;

public class Interactive {

    /***
     * Render the Interactive page
     * @param host
     * @param uri
     * @return
     */
    public static String IndexTemplate(String host, String uri) {
        return "<html>\n" +
                "   <head>\n" +
                "       <title>Interactive</title>\n" +
                "       <script type='text/javascript'>\n" +
                "           var _submitQuery = function() {\n" +
                "               var xhr = new XMLHttpRequest();\n" +
                "               xhr.open('POST', '"+host+"/post?query', true);\n" +
                "               xhr.setRequestHeader('Content-Type', 'application/json');\n" +
                "               xhr.onreadystatechange = function () {\n" +
                "                   if (this.readyState != 4) return;\n" +
                "                   if (this.status == 200) {\n" +
                "                       var data = '';\n" +
                "                       try {\n" +
                "                           data = JSON.parse(this.responseText);\n" +
                "                           htmlData = JSON.stringify(data);\n" +
                "                       } catch (ex) {\n" +
                "                           data = this.responseText;\n" +
                "                           htmlData = data;\n" +
                "                       }\n" +
                "                       console.log(data);\n" +
                "                       var all = document.getElementById('responseContainer').innerHTML;\n" +
                "                       document.getElementById('responseContainer').innerHTML = htmlData+'<br/>'+all;\n" +
                "                   }\n" +
                "               };\n" +
                "               xhr.send(JSON.stringify({\n" +
                "                   value: document.getElementById('txtQuery').value\n" +
                "               }));\n" +
                "           };\n" +
                "           function keyUpQuery(event) {\n" +
                "               if (event.keyCode == 13) {\n" +
                "                   var content = this.value;  \n" +
                "                   if(event.shiftKey){\n" +
                "                       _submitQuery();\n" +
                "                       event.stopPropagation();\n" +
                "                   }\n" +
                "               }\n" +
                "           }\n" +
                "           function populate(el) {\n" +
                "               document.getElementById('txtQuery').value = el.innerHTML;\n" +
                "           }\n" +
                "       </script>\n" +
                "       <style>\n" +
                "           #txtQuery {\n" +
                "               margin: 0px;\n" +
                "               width: 100%;\n" +
                "               min-height: 200px;\n" +
                "           }\n" +
                "           .populate {\n" +
                "               cursor:pointer;\n" +
                "           }\n" +
                "       </style>\n" +
                "   </head>\n" +
                "   <body>\n" +
                "       <h1>Interactive!</h1>\n" +
                "       <div>\n" +
                "           <div class='populate' onclick='populate(this)'>create database db1</div>\n" +
                "           <div class='populate' onclick='populate(this)'>show databases</div>\n" +
                "           <div class='populate' onclick='populate(this)'>create table db1.users</div>\n" +
                "           <div class='populate' onclick='populate(this)'>show db1 tables</div>\n" +
                "           <div class='populate' onclick='populate(this)'>insert into db1.users ('name') values('andrew')</div>\n" +
                "           <div class='populate' onclick='populate(this)'>new uuid test</div>\n" +
                "       </div>\n" +
                "       <div><textarea id='txtQuery' onkeyup='keyUpQuery(event)'></textarea></div>\n" +
                "       <div><input type='button' value='Submit query' onclick='_submitQuery()' /></div>\n" +
                "       <div id='responseContainer'></div>\n" +
                "   </body>\n" +
                "</html>";
    }
}
