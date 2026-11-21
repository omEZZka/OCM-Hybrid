(function(){
const O=window.OCM,h=O.h,S=O.S;
const KEY='engine';

// Mirrors Engines.kt. The built-in core is always available; the other three are installed
// into the Linux environment on demand.
const INTERFACES=[
  {id:'opencode',title:'OpenCode',tag:'встроенный',
   desc:'Чат, файлы, терминал, голос, медиа — всё внутри приложения.',
   icon:'chat',builtin:true},
  {id:'hermes',title:'Hermes Agent',tag:'Nous Research',
   desc:'Самообучающийся агент: навыки из опыта, память, поиск по прошлым сессиям.',
   icon:'star',pkg:'pip install hermes-agent'},
  {id:'titan',title:'TITAN',tag:'Mission Control',
   desc:'Локальная «команда» агентов, общая память, автономия, веб-панель.',
   icon:'cube',pkg:'npm i -g titan-agent'},
  {id:'titan-cli',title:'TITAN CLI',tag:'сжатие токенов',
   desc:'filter / compress / benchmark: режет до 70% токенов прямо в терминале.',
   icon:'terminal',pkg:'npm i -g titan-agent-cli'}
];

// Bridge JSON is parsed defensively: a failed call must not break the settings screen.
O.engines=()=>{try{return JSON.parse(OC.engines())}catch(e){return{engines:[],current:'opencode',state:{}}}};
O.engineCurrent=()=>{const e=O.engines();return e.current||O.store.get(KEY,'opencode')};
const stateOf=id=>{const e=O.engines();return (e.state&&e.state.id===id)?e.state:null};
const installed=()=>{try{return O.store.get('enginesInstalled',false)}catch(e){return false}};
const titleOf=id=>(INTERFACES.find(x=>x.id===id)||{}).title||id;

// Live install progress, kept outside the sheet so a redraw never loses it.
let inst={active:false,step:'',pct:0,error:null};

// One listener for native status pushes (MainActivity.watch emits "engine").
let lastSig='';
O.on('engine',d=>{
  const sig=JSON.stringify(d||{});
  if(sig===lastSig)return;lastSig=sig;
  if(d&&d.ready)O.toast(titleOf(d.id)+' '+O.tr('ready','готов'),1600);
  if(O.redrawInterfaces)O.redrawInterfaces();
});

O.on('enginesInstall',d=>{
  if(!d)return;
  if(d.error){inst={active:false,step:'',pct:0,error:String(d.error)};O.toast(O.tr('Install failed','Ошибка установки'),3000)}
  else if(d.done){inst={active:false,step:'',pct:100,error:null};O.store.set('enginesInstalled',true);O.toast(O.tr('Engines installed','Движки установлены'),2200)}
  else inst={active:true,step:d.step||'',pct:+d.pct||0,error:null};
  if(O.redrawInterfaces)O.redrawInterfaces();
});

function openPanel(id){
  try{OC.engineOpen(id)}catch(e){O.toast(String(e),2500)}
}

O.setEngine=id=>{
  O.store.set(KEY,id);
  try{OC.engineSelect(id)}catch(e){}
};

O.openInterfaces=()=>{
  const body=h('div');
  const s=O.sheet(O.tr('Interfaces','Интерфейсы'),body,{full:true,onClose:()=>{O.redrawInterfaces=null}});

  const pick=(it,closeAfter)=>{
    O.setEngine(it.id);
    if(it.id==='titan-cli'){
      if(closeAfter)s.close();
      setTimeout(()=>{O.setTab('term');setTimeout(()=>O.termRun&&O.termRun('titan help'),450)},closeAfter?260:0);
      return;
    }
    if(it.id==='opencode'){if(closeAfter)s.close();draw();return}
    if(!installed()){O.toast(O.tr('Install the engines first','Сначала установите движки'),2200);draw();return}
    if(closeAfter)s.close();
    const st=stateOf(it.id);
    if(st&&st.ready)setTimeout(()=>openPanel(it.id),closeAfter?300:0);
    else O.toast(O.tr('Starting ','Запуск ')+it.title+'…',1800);
    draw();
  };

  const draw=()=>{
    body.textContent='';
    const e=O.engines();
    const cur=e.current||'opencode';
    const instOk=installed();

    body.append(h('div',{class:'note',style:{margin:'2px 2px 10px'}},
      O.tr('Choose which agent interface the app uses. Every engine runs locally in the same Linux environment; only one is active at a time.',
           'Выберите, какой интерфейс агента использует приложение. Все движки работают локально в одном Linux-окружении; активен только один.')));

    INTERFACES.forEach(it=>{
      const active=cur===it.id;
      const st=stateOf(it.id);
      let sub=it.desc;
      if(active&&st&&it.id!=='opencode'){
        sub=st.ready?(O.tr('Running on port ','Работает на порту ')+st.port)
          :(st.phase==='starting'?O.tr('Starting…','Запускается…'):O.tr('Stopped','Остановлен'));
      }
      const right=h('span',{class:'eng-pill'+(active?' on':'')},
        active?O.tr('Active','Активен'):(it.builtin?'':it.tag));
      const row=O.listRow(it.icon,it.title,sub,right,()=>pick(it,true));
      if(!active)O.longPress(row,()=>{
        O.actions(it.title,[
          {label:O.tr('Select','Выбрать'),icon:'check',fn:()=>pick(it,false)},
          ...(it.builtin?[]:[{label:O.tr('Open panel','Открыть панель'),icon:'ext',fn:()=>openPanel(it.id)}])
        ]);
      },500);
      body.append(row);
      if(active&&st&&st.error){
        body.append(h('div',{class:'note err',style:{margin:'2px 2px 8px',whiteSpace:'pre-wrap',fontSize:'11px'}},String(st.error).slice(0,600)));
      }
    });

    const curSt=stateOf(cur);
    if(curSt&&curSt.ready&&cur!=='opencode'){
      body.append(h('button',{class:'btn pri wide',style:{marginTop:'10px'},onclick:()=>{s.close();openPanel(cur)}},
        O.icon('ext'),O.tr('Open ','Открыть ')+titleOf(cur)));
      body.append(h('button',{class:'btn wide',style:{marginTop:'8px'},onclick:()=>{
        try{OC.engineStop()}catch(_){}
        O.toast(O.tr('Engine stopped','Движок остановлен'),1500);
        setTimeout(draw,400);
      }},O.icon('stop'),O.tr('Stop engine','Остановить движок')));
    }
    if(cur==='titan-cli'){
      body.append(h('button',{class:'btn pri wide',style:{marginTop:'10px'},onclick:()=>{s.close();O.setTab('term');setTimeout(()=>O.termRun&&O.termRun('titan help'),400)}},
        O.icon('terminal'),O.tr('Open terminal','Открыть терминал')));
    }

    if(inst.error)body.append(h('div',{class:'note err',style:{margin:'10px 2px',whiteSpace:'pre-wrap',fontSize:'11px'}},inst.error.slice(0,900)));

    body.append(h('div',{class:'grp'},O.tr('External engines','Внешние движки')));
    if(inst.active){
      const bar=h('i',{style:{width:inst.pct+'%'}});
      body.append(h('div',null,
        h('div',{class:'bar',style:{width:'100%'}},bar),
        h('p',{class:'note'},inst.step+' — '+inst.pct+'%')));
    }else{
      body.append(h('div',{class:'note',style:{margin:'0 2px 8px',fontSize:'11.5px'}},
        instOk?O.tr('Hermes, TITAN and TITAN CLI are installed.','Hermes, TITAN и TITAN CLI установлены.')
          :O.tr('Downloads Python and Node packages into the Linux environment (several hundred MB, needs internet).',
                'Скачивает Python- и Node-пакеты в Linux-окружение (несколько сотен МБ, нужен интернет).')));
      body.append(h('button',{class:'btn '+(instOk?'':'pri')+' wide',onclick:()=>{
        inst={active:true,step:O.tr('Starting…','Запуск…'),pct:0,error:null};draw();
        try{OC.enginesInstall()}catch(err){inst={active:false,step:'',pct:0,error:String(err)};draw()}
      }},O.icon('down'),instOk?O.tr('Reinstall engines','Переустановить движки'):O.tr('Install engines','Установить движки')));
    }

    body.append(h('div',{class:'note',style:{marginTop:'10px',fontSize:'11px'}},
      O.tr('Engines run under proot; their web panels open in a sandboxed view with no access to app data.',
           'Движки работают под proot; их веб-панели открываются в изолированном окне без доступа к данным приложения.')));
  };

  O.redrawInterfaces=()=>{if(!s._closed)draw()};
  draw();
  return s;
};
})();