# intern-partialAPI
Just record what I have done while I was a intern.  

## API develop  
Using Vert.x to develop API service.  
## framework
There're verticles to construct this framework.  

There's a verticle APIService that every request goes to then it pass to corresponding controller verticle by the path.  
There's a verticle BaseController that every controller extend to. It contains the part of interact with APIService.  
There're several controllers which contains route pathes, parameters, functions and responses.  


